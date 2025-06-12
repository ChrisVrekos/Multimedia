package com.multisrv;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// Add these missing imports


public class Server {
    private static final int MAX_CONNECTIONS = 100;
    private static final ExecutorService clientPool = Executors.newCachedThreadPool();
    private static final AtomicInteger activeConnections = new AtomicInteger(0);
    private static final VideoManager videoManager = new VideoManager();
    private static final Logger logger = LoggerFactory.getLogger(Server.class);
    private static final String DEFAULT_SERVER_ID   = "1";
    private static final String DEFAULT_SERVER_PORT = "5058";
    private static final String DEFAULT_VIDEO_PATH  = "./multisrv/src/main/java/com/multisrv/videos";
    private static final String DEFAULT_FFMPEG_PATH = "/usr/bin";
    public static final String SERVER_ID   =
            System.getenv().getOrDefault("SERVER_ID", DEFAULT_SERVER_ID);

        public static final int SERVER_PORT   =
            Integer.parseInt(System.getenv().getOrDefault("SERVER_PORT", DEFAULT_SERVER_PORT));

        public static final String VIDEO_PATH =
            System.getenv().getOrDefault("VIDEO_PATH", DEFAULT_VIDEO_PATH);

        public static final String FFMPEG_PATH =
            System.getenv().getOrDefault("FFMPEG_PATH", DEFAULT_FFMPEG_PATH);

    public static void main(String[] args) {
        
        int port = SERVER_PORT;
                // Register shutdown hook
        registerShutdownHook();
        
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            logger.info("Server "+ SERVER_ID + " running on port " + port);
            
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
                    logger.error("Error accepting connection: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            logger.error("Server error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Server interrupted");
        }
    }
    
    private static void handleNewConnection(Socket clientSocket) {
        try {
            logger.info("New client connected: " + clientSocket);
            activeConnections.incrementAndGet();
            
            // Create streams
            DataInputStream input = new DataInputStream(clientSocket.getInputStream());
            DataOutputStream output = new DataOutputStream(clientSocket.getOutputStream());
            
            // Submit to thread pool the last argument is unnamed class dissconnectCallabck that impliments 
            //Runnable so when the client disconnects the disconnectCallback.run() is called and the 
            //below code is executed line 65-66
            clientPool.submit(new ClientHandler(clientSocket, input, output, videoManager, () -> {
                activeConnections.decrementAndGet();
                logger.info("Client disconnected. Active connections: " + activeConnections.get());
            }));
            
        } catch (IOException e) {
            logger.error("Error handling connection: " + e.getMessage());
            activeConnections.decrementAndGet();
        }
    }
    
    private static void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down server...");
            clientPool.shutdown();
            try {
                if (!clientPool.awaitTermination(5, TimeUnit.SECONDS)) {
                    clientPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                clientPool.shutdownNow();
            }
            logger.info("Server shutdown complete");
        }));
    }
}

