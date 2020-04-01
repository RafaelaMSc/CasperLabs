import { WindowPostMessageProxy } from '@ont-community/window-post-message-proxy';
import { Rpc } from './rpc';
import { Request } from './tunnel';

let windowProxy: WindowPostMessageProxy;
let rpc: Rpc;

export function registerClient(
  {
    logMessages = false,
    logWarnings = false
  }: {
    logMessages?: boolean;
    logWarnings?: boolean;
  }
) {
  windowProxy = new WindowPostMessageProxy({
    name: 'page',
    target: 'content-script',
    logMessages,
    suppressWarnings: !logWarnings
  });
  rpc = new Rpc({
    source: 'page',
    destination: 'background',
    logMessages: false,
    postMessage: (msg: Request) => windowProxy.postMessage(window, msg)
  });
}

export async function call<RESULT>(method: string, ...params: any[]) {
  return rpc.call<RESULT>(method, ...params);
}