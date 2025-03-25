package com.multisrv;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class Server {
    private static final int DEFAULT_PORT = 5058;
    private static final int MAX_CONNECTIONS = 100;
    private static final ExecutorService clientPool = Executors.newCachedThreadPool();
    private static final AtomicInteger activeConnections = new AtomicInteger(0);
    private static final VideoManager videoManager = new VideoManager();
    
    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        
        // Register shutdown hook
        registerShutdownHook();
        
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Server running on port " + port);
            
            // Initialize video manager
            videoManager.initialize();
            
            // Accept client connections
            while (true) {
                if (activeConnections.get() >= MAX_CONNECTIONS) {
                    // Simple load balancing: wait if at max capacity
                    Thread.sleep(100);
                    continue;
                }
                
                // Accept new connection
                try {
                    Socket clientSocket = serverSocket.accept();
                    handleNewConnection(clientSocket);
                } catch (IOException e) {
                    System.err.println("Error accepting connection: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Server interrupted");
        }
    }
    
    private static void handleNewConnection(Socket clientSocket) {
        try {
            System.out.println("New client connected: " + clientSocket);
            activeConnections.incrementAndGet();
            
            // Create streams
            DataInputStream input = new DataInputStream(clientSocket.getInputStream());
            DataOutputStream output = new DataOutputStream(clientSocket.getOutputStream());
            
            // Submit to thread pool the last argument is unnamed class dissconnectCallabck that impliments 
            //Runnable so when the client disconnects the disconnectCallback.run() is called and the 
            //below code is executed line 65-66
            clientPool.submit(new ClientHandler(clientSocket, input, output, videoManager, () -> {
                activeConnections.decrementAndGet();
                System.out.println("Client disconnected. Active connections: " + activeConnections.get());
            }));
            
        } catch (IOException e) {
            System.err.println("Error handling connection: " + e.getMessage());
            activeConnections.decrementAndGet();
        }
    }
    
    private static void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down server...");
            clientPool.shutdown();
            try {
                if (!clientPool.awaitTermination(5, TimeUnit.SECONDS)) {
                    clientPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                clientPool.shutdownNow();
            }
            System.out.println("Server shutdown complete");
        }));
    }
}

