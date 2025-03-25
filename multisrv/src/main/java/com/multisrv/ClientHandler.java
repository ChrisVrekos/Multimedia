package com.multisrv;

import java.io.*;
import java.net.*;

public class ClientHandler implements Runnable {
    private final Socket socket;
    private final DataInputStream input;
    private final DataOutputStream output;
    private final VideoManager videoManager;
    private final Runnable disconnectCallback;
    
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
            output.writeUTF("Welcome to the Multimedia Server");
            
            // Process client requests
            processClientRequests();
            
        } catch (IOException e) {
            System.err.println("Error in client handler: " + e.getMessage());
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
                System.out.println("From client: " + message);
                
                if (message.equalsIgnoreCase("Bye")) {
                    System.out.println("Client " + socket + " sends exit...");
                    break;
                }
                
                // Process commands and respond to client
                String response = processCommand(message);
                output.writeUTF(response);
                
            } catch (SocketException | EOFException e) {
                System.out.println("Client " + socket + " disconnected abruptly");
                break;
            }
        }
    }
    
    private String processCommand(String command) {
        // Here you can add proper command handling
        // For example: list videos, get video info, etc.
        if (command.startsWith("LIST")) {
            return videoManager.getVideoList();
        } else if (command.startsWith("GET ")) {
            String videoName = command.substring(4);
            return videoManager.getVideoInfo(videoName);
        }
        
        return "Unknown command";
    }
    
    private void closeResources() {
        try {
            if (input != null) input.close();
            if (output != null) output.close();
            if (socket != null) socket.close();
        } catch (IOException e) {
            System.err.println("Error closing resources: " + e.getMessage());
        }
    }
}