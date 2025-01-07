package org.chernovia.chess;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.logging.Logger;

public class LichessWebSock extends WebSocketClient {

    public static ObjectMapper mapper = new ObjectMapper();
    URI uri;
    Logger logger = Logger.getLogger("Lichess");

    public LichessWebSock(URI serverURI) {
        super(serverURI);
        uri = serverURI;
    }

    public void log(String message) {
        logger.info(message);
    }
    @Override
    public void onOpen(ServerHandshake handshakedata) {
        log("opened connection: " + uri);
    }

    @Override
    public void onMessage(String message) { //log("received: " + message);
        log("received message: " + message);
    }

    @Override
    public void onClose(int code, String reason, boolean remote) { // The close codes are documented in class org.java_websocket.framing.CloseFrame
        log("Lobby connection closed by " + (remote ? "remote peer" : "us") + " Code: " + code + " Reason: " + reason);

    }

    @Override
    public void onError(Exception ex) {
        ex.printStackTrace(); // if the error is fatal then onClose will be called additionally
    }

}

