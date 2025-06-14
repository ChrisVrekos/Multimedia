package com.multisrv;

import java.io.*;
import java.net.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClientHandler implements Runnable {
    private final Socket socket;
    private final DataInputStream input;
    private final DataOutputStream output;
    private final VideoManager videoManager;
    private final Runnable disconnectCallback;
    private static final Logger logger = LoggerFactory.getLogger(ClientHandler.class);

    
    public ClientHandler(Socket socket, DataInputStream input, DataOutputStream output, 
                         VideoManager videoManager, Runnable disconnectCallback) {
        this.socket = socket;
        this.input = input;
        this.output = output;
        this.videoManager = videoManager;
        this.disconnectCallback = disconnectCallback;
    }
    
    @Override
    public void run() {
        try {
            // Send welcome message
            output.writeUTF("Welcome to the Multimedia Server running on Server instance " + Server.SERVER_ID);
            
            // Process client requests
            processClientRequests();
            
        } catch (IOException e) {
            ClientHandler.logger.error("Error in client handler: " + e.getMessage());
        } finally {
            closeResources();
            disconnectCallback.run();
        }
    }
    
    private void processClientRequests() throws IOException {
        String message;
        while (true) {
            try {
                message = input.readUTF();
                ClientHandler.logger.info("From client: " + message);
                
                if (message.equalsIgnoreCase("Bye")) {
                    ClientHandler.logger.info("Client " + socket + " sends exit...");
                    break;
                }
                
                // Process commands and respond to client
                String response = processCommand(message);
                output.writeUTF(response);
                
            } catch (SocketException | EOFException e) {
                ClientHandler.logger.info("Client " + socket + " disconnected abruptly");
                break;
            }
        }
    }
    
    private String processCommand(String command) {
        if (command.startsWith("LIST")) {
            return videoManager.getVideoList();
        } else if (command.startsWith("GET ")) {
            String videoName = command.substring(4);
            return videoManager.getVideoInfo(videoName);
        } else if (command.startsWith("PLAY")) {
            // Parse video name and protocol
            String requestParams = command.substring(5).trim();
            String videoName;
            String protocol = "UDP"; 
            
            // Check if protocol is specified
            if (requestParams.contains("PROTOCOL=")) {
                // Extract protocol
                int protocolIndex = requestParams.indexOf("PROTOCOL=");
                String protocolParam = requestParams.substring(protocolIndex + 9);
                protocol = protocolParam.trim();
                
                // Extract video name (everything before PROTOCOL=, trimmed)
                videoName = requestParams.substring(0, protocolIndex).trim();
            } else {
                videoName = requestParams;
            }
            
            ClientHandler.logger.info("Requested video: " + videoName + " with protocol: " + protocol);
            return videoManager.playVideo(videoName, protocol);
        }
        
        return "Unknown command";
    }
    
    private void closeResources() {
        try {
            if (input != null) input.close();
            if (output != null) output.close();
            if (socket != null) socket.close();
        } catch (IOException e) {
            ClientHandler.logger.error("Error closing resources: " + e.getMessage());
        }
    }
}