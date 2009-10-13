/*
 * Copyright 2009 Google Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.gwt.dev.shell;

import com.google.gwt.core.ext.TreeLogger;
import com.google.gwt.dev.shell.BrowserChannel.SessionHandler.ExceptionOrReturnValue;
import com.google.gwt.dev.util.log.PrintWriterTreeLogger;

import java.io.IOException;
import java.net.Socket;
import java.util.Set;

/**
 * Implementation of the BrowserChannel for the client side.
 * 
 */
public class BrowserChannelClient extends BrowserChannel {

  private static class ClientObjectRefFactory implements ObjectRefFactory {

    private final RemoteObjectTable<JavaObjectRef> remoteObjectTable;

    public ClientObjectRefFactory() {
      remoteObjectTable = new RemoteObjectTable<JavaObjectRef>();
    }

    public JavaObjectRef getJavaObjectRef(int refId) {
      JavaObjectRef objectRef = remoteObjectTable.getRemoteObjectRef(refId);
      if (objectRef == null) {
        objectRef = new JavaObjectRef(refId);
        remoteObjectTable.putRemoteObjectRef(refId, objectRef);
      }
      return objectRef;
    }

    public JsObjectRef getJsObjectRef(int refId) {
      return new JsObjectRef(refId);
    }

    public Set<Integer> getRefIdsForCleanup() {
      return remoteObjectTable.getRefIdsForCleanup();
    }
  }

  private final HtmlUnitSessionHandler htmlUnitSessionHandler;
  private final PrintWriterTreeLogger logger = new PrintWriterTreeLogger();
  private final String moduleName;
  private final String tabKey;
  private final String sessionKey;
  private final String url;
  private final String versionString;
  private boolean connected = false;
  private boolean shouldDisconnect = false;
  private int protocolVersion;

  public BrowserChannelClient(String addressParts[], String url,
      String sessionKey, String moduleName, String versionString,
      HtmlUnitSessionHandler htmlUnitSessionHandler) throws IOException {
    super(new Socket(addressParts[0], Integer.parseInt(addressParts[1])),
        new ClientObjectRefFactory());
    connected = true;
    this.url = url;
    this.sessionKey = sessionKey;
    this.moduleName = moduleName;
    this.tabKey = ""; // TODO(jat): update when tab support is added.
    this.versionString = versionString;
    logger.setMaxDetail(TreeLogger.WARN);
    logger.log(TreeLogger.SPAM, "BrowserChannelClient, versionString: "
        + versionString);
    this.htmlUnitSessionHandler = htmlUnitSessionHandler;
  }

  public boolean disconnectFromHost() throws IOException {
    logger.log(TreeLogger.DEBUG, "disconnecting channel " + this);
    if (!isConnected()) {
      logger.log(TreeLogger.DEBUG,
          "Disconnecting already disconnected channel " + this);
      return false;
    }
    new QuitMessage(this).send();
    endSession();
    connected = false;
    return true;
  }

  /**
   * @return the negotiated protocol version -- only valid after {@link #init}
   * has returned.
   */
  public int getProtocolVersion() {
    return protocolVersion;
  }

  public boolean isConnected() {
    return connected;
  }

  // TODO (amitmanjhi): refer the state (message?) transition diagram
  /**
   * returns true iff execution completes normally.
   */
  public boolean process() throws IOException, BrowserChannelException {
    if (!init()) {
      disconnectFromHost();
      return false;
    }
    logger.log(TreeLogger.DEBUG, "sending " + MessageType.LOAD_MODULE
        + " message, userAgent: " + htmlUnitSessionHandler.getUserAgent());
    ReturnMessage returnMessage = null;
    synchronized (htmlUnitSessionHandler.getHtmlPage()) {
      new LoadModuleMessage(this, url, tabKey, sessionKey, moduleName,
          htmlUnitSessionHandler.getUserAgent()).send();
      returnMessage = reactToMessages(htmlUnitSessionHandler, true);
      if (shouldDisconnect) {
        disconnectFromHost();
      }
    }
    logger.log(TreeLogger.DEBUG, "loaded module, returnValue: "
        + returnMessage.getReturnValue() + ", isException: "
        + returnMessage.isException());
    return !returnMessage.isException();
  }

  public ReturnMessage reactToMessagesWhileWaitingForReturn(
      HtmlUnitSessionHandler handler) throws IOException,
      BrowserChannelException {
    ReturnMessage returnMessage = reactToMessages(handler, true);
    if (shouldDisconnect) {
      disconnectFromHost();
    }
    return returnMessage;
  }

  void setShouldDisconnect() {
    shouldDisconnect = true;
  }

  /*
   * Perform the initial interaction. Return true if interaction succeeds, false
   * if it fails. Do a check protocol versions, expected with 2.0+ oophm
   * protocol.
   */
  private boolean init() throws IOException, BrowserChannelException {
    logger.log(TreeLogger.DEBUG, "sending " + MessageType.CHECK_VERSIONS
        + " message");
    new CheckVersionsMessage(this, PROTOCOL_VERSION_OLDEST,
        PROTOCOL_VERSION_CURRENT, versionString).send();
    MessageType type = Message.readMessageType(getStreamFromOtherSide());
    switch (type) {
      case PROTOCOL_VERSION:
        ProtocolVersionMessage protocolMessage = ProtocolVersionMessage.receive(this);
        protocolVersion = protocolMessage.getProtocolVersion();
        logger.log(TreeLogger.DEBUG, MessageType.PROTOCOL_VERSION
            + ": protocol version = " + protocolVersion);
        break;
      case FATAL_ERROR:
        FatalErrorMessage errorMessage = FatalErrorMessage.receive(this);
        logger.log(TreeLogger.ERROR, "Received FATAL_ERROR message "
            + errorMessage.getError());
        return false;
      default:
        return false;
    }

    return true;
  }

  private ReturnMessage reactToMessages(
      HtmlUnitSessionHandler htmlUnitSessionHandler, boolean expectReturn)
      throws IOException, BrowserChannelException {
    while (true) {
      ExceptionOrReturnValue returnValue;
      MessageType type = Message.readMessageType(getStreamFromOtherSide());
      logger.log(TreeLogger.INFO, "client: received " + type + ", thread: "
          + Thread.currentThread().getName());
      try {
        switch (type) {
          case INVOKE:
            InvokeOnClientMessage invokeMessage = InvokeOnClientMessage.receive(this);
            returnValue = htmlUnitSessionHandler.invoke(this,
                invokeMessage.getThis(), invokeMessage.getMethodName(),
                invokeMessage.getArgs());
            htmlUnitSessionHandler.sendFreeValues(this);
            new ReturnMessage(this, returnValue.isException(),
                returnValue.getReturnValue()).send();           
            break;
          case INVOKE_SPECIAL:
            InvokeSpecialMessage invokeSpecialMessage = InvokeSpecialMessage.receive(this);
            logger.log(TreeLogger.DEBUG, type + " message " + ", thisRef: "
                + invokeSpecialMessage.getArgs());
            returnValue = htmlUnitSessionHandler.invokeSpecial(this,
                invokeSpecialMessage.getDispatchId(),
                invokeSpecialMessage.getArgs());
            htmlUnitSessionHandler.sendFreeValues(this);
            new ReturnMessage(this, returnValue.isException(),
                returnValue.getReturnValue()).send();
            break;
          case FREE_VALUE:
            FreeMessage freeMessage = FreeMessage.receive(this);
            logger.log(TreeLogger.DEBUG, type + " message "
                + freeMessage.getIds());
            htmlUnitSessionHandler.freeValue(this, freeMessage.getIds());
            // no response
            break;
          case LOAD_JSNI:
            LoadJsniMessage loadJsniMessage = LoadJsniMessage.receive(this);
            String jsniString = loadJsniMessage.getJsni();
            htmlUnitSessionHandler.loadJsni(this, jsniString);
            // no response
            break;
          case REQUEST_ICON:
            RequestIconMessage.receive(this);
            // no need for icon here 
            UserAgentIconMessage.send(this, null);
            break;
          case RETURN:
            if (!expectReturn) {
              logger.log(TreeLogger.ERROR, "Received unexpected "
                  + MessageType.RETURN);
            }
            return ReturnMessage.receive(this);
          case QUIT:
            if (expectReturn) {
              logger.log(TreeLogger.ERROR, "Received " + MessageType.QUIT
                  + " while waiting for return");
            }
            disconnectFromHost();
            return null;
          default:
            logger.log(TreeLogger.ERROR, "Unkown messageType: " + type
                + ", expectReturn: " + expectReturn);
            disconnectFromHost();
            return null;
        }
      } catch (Exception ex) {
        logger.log(TreeLogger.ERROR, "Unknown exception" + ex);
        ex.printStackTrace();
      }
    }
  }
}
