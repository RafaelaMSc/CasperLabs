package io.casperlabs.casper.finality

import cats.Monad
import cats.implicits._
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.finality.votingmatrix.VotingMatrix.{Vote, VotingMatrix}
import io.casperlabs.catscontrib.MonadStateOps._
import io.casperlabs.models.Message.MainRank
import io.casperlabs.models.{Message, Weight}
import io.casperlabs.storage.dag.DagRepresentation
import io.casperlabs.casper.validation.Validation

import scala.annotation.tailrec
import scala.collection.mutable.{IndexedSeq => MutableSeq}

package object votingmatrix {

  import io.casperlabs.catscontrib.MonadThrowable

  import Weight.Implicits._
  import Weight.Zero

  /**
    * Updates voting matrix when a new block added to dag
    * @param dag
    * @param msg the new message
    * @param lfbChild which branch the new block vote for
    * @return
    */
  def updateVoterPerspective[F[_]: Monad](
      dag: DagRepresentation[F],
      msg: Message,
      messagePanorama: Map[Validator, Message],
      lfbChild: BlockHash,
      isHighway: Boolean
  )(implicit matrix: VotingMatrix[F]): F[Unit] =
    for {
      validatorToIndex <- (matrix >> 'validatorToIdx).get
      voter            = msg.validatorId
      _ <- if (!validatorToIndex.contains(voter)) {
            // The creator of block isn't from the validatorsSet
            // e.g. It is bonded after creating the latestFinalizedBlock
            ().pure[F]
          } else {
            for {
              _ <- updateVotingMatrixOnNewBlock[F](dag, msg, messagePanorama, isHighway)
              _ <- updateFirstZeroLevelVote[F](voter, lfbChild, msg.mainRank)
            } yield ()
          }
    } yield ()

  /**
    * Checks whether provide branch should be finalized
    * @param rFTT the relative fault tolerance threshold
    * @return
    */
  def checkForCommittee[F[_]: MonadThrowable](
      dag: DagRepresentation[F],
      rFTT: Double,
      isHighway: Boolean
  )(
      implicit matrix: VotingMatrix[F]
  ): F[Option[CommitteeWithConsensusValue]] =
    for {
      weightMap   <- (matrix >> 'weightMap).get
      totalWeight = weightMap.values.sum
      quorum      = totalWeight * (rFTT + 0.5)
      committee <- findCommitteeApproximation[F](dag, quorum, isHighway)
                    .flatMap {
                      case Some(
                          CommitteeWithConsensusValue(committeeApproximation, _, consensusValue)
                          ) =>
                        for {
                          votingMatrix        <- (matrix >> 'votingMatrix).get
                          firstLevelZeroVotes <- (matrix >> 'firstLevelZeroVotes).get
                          validatorToIndex    <- (matrix >> 'validatorToIdx).get
                          // A sequence of bits where 1 represents an i-th validator present
                          // in the committee approximation.
                          validatorsMask = FinalityDetectorUtil
                            .fromMapToArray(validatorToIndex, committeeApproximation.contains)
                          // A sequence of validators' weights.
                          weight = FinalityDetectorUtil
                            .fromMapToArray(validatorToIndex, weightMap.getOrElse(_, Zero))
                          committee = pruneLoop(
                            votingMatrix,
                            firstLevelZeroVotes,
                            consensusValue,
                            validatorsMask,
                            quorum,
                            weight
                          ) map {
                            case (mask, totalWeight) =>
                              val committee = validatorToIndex.filter { case (_, i) => mask(i) }.keySet
                              CommitteeWithConsensusValue(committee, totalWeight, consensusValue)
                          }
                        } yield committee
                      case None =>
                        none[CommitteeWithConsensusValue].pure[F]
                    }
    } yield committee

  private[votingmatrix] def updateVotingMatrixOnNewBlock[F[_]: Monad](
      dag: DagRepresentation[F],
      msg: Message,
      messagePanorama: Map[Validator, Message],
      isHighway: Boolean
  )(implicit matrix: VotingMatrix[F]): F[Unit] =
    for {
      validatorToIndex <- (matrix >> 'validatorToIdx).get
      panoramaM <- FinalityDetectorUtil
                    .panoramaM[F](dag, validatorToIndex, msg, messagePanorama, isHighway)
      // Replace row i in voting-matrix by panoramaM
      _ <- (matrix >> 'votingMatrix).modify(
            _.updated(validatorToIndex(msg.validatorId), panoramaM)
          )
    } yield ()

  private[votingmatrix] def updateFirstZeroLevelVote[F[_]: Monad](
      validator: Validator,
      newVote: BlockHash,
      dagLevel: MainRank
  )(implicit matrix: VotingMatrix[F]): F[Unit] =
    for {
      firstLevelZeroMsgs <- (matrix >> 'firstLevelZeroVotes).get
      validatorToIndex   <- (matrix >> 'validatorToIdx).get
      voterIdx           = validatorToIndex(validator)
      firstLevelZeroVote = firstLevelZeroMsgs(voterIdx)
      _ <- firstLevelZeroVote match {
            case None =>
              (matrix >> 'firstLevelZeroVotes).modify(
                _.updated(voterIdx, Some((newVote, dagLevel)))
              )
            case Some((prevVote, _)) if prevVote != newVote =>
              (matrix >> 'firstLevelZeroVotes).modify(
                _.updated(voterIdx, Some((newVote, dagLevel)))
              )
            case Some((prevVote, _)) if prevVote == newVote =>
              ().pure[F]
          }
    } yield ()

  /**
    * Phase 1 - finding most supported consensus value,
    * if its supporting vote larger than quorum, return it and its supporter
    * else return None
    * @param quorum
    * @return
    */
  private[votingmatrix] def findCommitteeApproximation[F[_]: MonadThrowable](
      dag: DagRepresentation[F],
      quorum: Weight,
      isHighway: Boolean
  )(implicit matrix: VotingMatrix[F]): F[Option[CommitteeWithConsensusValue]] =
    for {
      weightMap           <- (matrix >> 'weightMap).get
      validators          <- (matrix >> 'validators).get
      firstLevelZeroVotes <- (matrix >> 'firstLevelZeroVotes).get
      // Get Map[VoteBranch, List[Validator]] directly from firstLevelZeroVotes
      committee <- if (firstLevelZeroVotes.isEmpty) {
                    // No one voted on anything in the current b-game
                    none[CommitteeWithConsensusValue].pure[F]
                  } else
                    firstLevelZeroVotes.zipWithIndex
                      .collect {
                        case (Some((blockHash, _)), idx) =>
                          (blockHash, validators(idx))
                      }
                      .toList
                      .filterA {
                        case (voteHash, validator) =>
                          // Get rid of validators' votes.
                          // Need to cater for both NCB and Highway modes.
                          val highwayCheck = for {
                            voteMsg <- dag.lookupUnsafe(voteHash)
                            eraEquivocators <- dag.getEquivocatorsInEra(
                                                voteMsg.eraId
                                              )
                          } yield !eraEquivocators.contains(validator)

                          val ncbCheck =
                            dag.getEquivocators.map(!_.contains(validator))

                          if (isHighway) highwayCheck else ncbCheck
                      }
                      .map(_.groupBy(_._1).mapValues(_.map(_._2)))
                      .map { consensusValueToHonestValidators =>
                        if (consensusValueToHonestValidators.isEmpty)
                          // After filtering out equivocators we don't have any honest votes.
                          none[CommitteeWithConsensusValue]
                        else {
                          // Get most support voteBranch and its support weight
                          val mostSupport = consensusValueToHonestValidators
                            .mapValues(_.map(weightMap.getOrElse(_, Zero)).sum)
                            .maxBy(_._2)
                          val (voteValue, supportingWeight) = mostSupport
                          // Get the voteBranch's supporters
                          val supporters = consensusValueToHonestValidators(voteValue)
                          if (supportingWeight > quorum) {
                            Some(
                              CommitteeWithConsensusValue(
                                supporters.toSet,
                                supportingWeight,
                                voteValue
                              )
                            )
                          } else None
                        }
                      }
    } yield committee

  @tailrec
  private[votingmatrix] def pruneLoop(
      matrix: MutableSeq[MutableSeq[Level]],
      firstLevelZeroVotes: MutableSeq[Option[Vote]],
      candidateBlockHash: BlockHash,
      mask: MutableSeq[Boolean],
      q: Weight,
      weight: MutableSeq[Weight]
  ): Option[(MutableSeq[Boolean], Weight)] = {
    val (newMask, prunedValidator, maxTotalWeight) = matrix.zipWithIndex
      .filter { case (_, rowIndex) => mask(rowIndex) }
      .foldLeft((mask, false, Zero)) {
        case ((newMask, prunedValidator, maxTotalWeight), (row, rowIndex)) =>
          val voteSum = row.zipWithIndex
            .filter { case (_, columnIndex) => mask(columnIndex) }
            .map {
              case (latestDagLevelSeen, columnIndex) =>
                firstLevelZeroVotes(columnIndex).fold(Zero) {
                  case (consensusValue, dagLevelOf1stLevel0) =>
                    if (consensusValue == candidateBlockHash && dagLevelOf1stLevel0 <= latestDagLevelSeen)
                      weight(columnIndex)
                    else Zero
                }
            }
            .sum
          if (voteSum < q) {
            (newMask.updated(rowIndex, false), true, maxTotalWeight)
          } else {
            (newMask, prunedValidator, maxTotalWeight + weight(rowIndex))
          }
      }
    if (prunedValidator) {
      if (maxTotalWeight < q)
        // Terminate finality detection, finality is not reached yet.
        None
      else
        pruneLoop(matrix, firstLevelZeroVotes, candidateBlockHash, newMask, q, weight)
    } else {
      (mask, maxTotalWeight).some
    }
  }

}
