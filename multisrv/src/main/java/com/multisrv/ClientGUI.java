package com.multisrv;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ClientGUI extends Application {
    private Socket socket;
    private DataInputStream input;
    private DataOutputStream output;
    private TextArea messageArea;
    
    // Video selection controls
    private ComboBox<String> videoSelector = new ComboBox<>();
    private ComboBox<String> qualitySelector = new ComboBox<>();
    private ComboBox<String> formatSelector = new ComboBox<>();
    private ComboBox<String> protocolSelector = new ComboBox<>();  // New protocol selector
    private Button playButton = new Button("Play");
    
    // Current process reference
    private final AtomicReference<Process> currentProcess = new AtomicReference<>(null);
    
    // Video metadata storage - maps video name to its qualities and formats
    private Map<String, VideoMetadata> videoMetadata = new HashMap<>();
    
    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("Multimedia Client");

        // Message display area
        messageArea = new TextArea();
        messageArea.setEditable(false);
        messageArea.setWrapText(true);

        // Text input area
        TextField commandField = new TextField();
        Button sendButton = new Button("Send");
        
        // Control buttons
        Button listButton = new Button("List Videos");
        
        // Setup quality and format selectors
        videoSelector.setPromptText("Select video");
        qualitySelector.setPromptText("Select quality");
        formatSelector.setPromptText("Select format");
        
        // Setup protocol selector with the four options
        protocolSelector.getItems().addAll("UDP", "TCP", "RTP/UDP", "HLS");
        protocolSelector.setValue("UDP");  // Default to UDP
        protocolSelector.setPromptText("Select protocol");
        
        videoSelector.setMaxWidth(Double.MAX_VALUE);
        qualitySelector.setMaxWidth(Double.MAX_VALUE);
        formatSelector.setMaxWidth(Double.MAX_VALUE);
        protocolSelector.setMaxWidth(Double.MAX_VALUE);
        
        // Handle protocol selection change - disable quality/format for HLS
        protocolSelector.setOnAction(e -> {
            String selectedProtocol = protocolSelector.getValue();
            boolean isHLS = "HLS".equals(selectedProtocol);
            
            // Disable quality and format selectors for HLS streaming
            qualitySelector.setDisable(isHLS);
            formatSelector.setDisable(isHLS);
            
            if (isHLS) {
                showMessage("HLS selected: Quality and format will be managed adaptively by the server");
            }
        });
        
        // Handle video selection change - update quality and format options
        videoSelector.setOnAction(e -> {
            String selectedVideo = videoSelector.getValue();
            if (selectedVideo != null && videoMetadata.containsKey(selectedVideo)) {
                VideoMetadata metadata = videoMetadata.get(selectedVideo);
                
                qualitySelector.getItems().clear();
                qualitySelector.getItems().addAll(metadata.getQualities());
                if (!metadata.getQualities().isEmpty()) {
                    qualitySelector.setValue(metadata.getQualities().get(0));
                }
                
                formatSelector.getItems().clear();
                formatSelector.getItems().addAll(metadata.getFormats());
                if (!metadata.getFormats().isEmpty()) {
                    formatSelector.setValue(metadata.getFormats().get(0));
                }
                
                // Make sure protocol selection is applied (in case HLS was selected)
                protocolSelector.fireEvent(new javafx.event.ActionEvent());
            }
        });
        
        // Grid for better organized controls
        GridPane controlGrid = new GridPane();
        controlGrid.setHgap(10);
        controlGrid.setVgap(8);
        controlGrid.setPadding(new Insets(10));
        
        controlGrid.add(new Label("Video:"), 0, 0);
        controlGrid.add(videoSelector, 1, 0);
        controlGrid.add(new Label("Protocol:"), 0, 1);  // Add protocol label and selector
        controlGrid.add(protocolSelector, 1, 1);
        controlGrid.add(new Label("Quality:"), 0, 2);
        controlGrid.add(qualitySelector, 1, 2);
        controlGrid.add(new Label("Format:"), 0, 3);
        controlGrid.add(formatSelector, 1, 3);
        
        HBox buttonBox = new HBox(10);
        buttonBox.setPadding(new Insets(5, 0, 0, 0));
        buttonBox.getChildren().addAll(playButton, listButton);
        controlGrid.add(buttonBox, 1, 4);  // Moved down one row
        
        // Input area
        HBox inputBox = new HBox(10);
        inputBox.setPadding(new Insets(10));
        inputBox.getChildren().addAll(commandField, sendButton);
        
        // Main layout
        BorderPane mainLayout = new BorderPane();
        mainLayout.setTop(controlGrid);
        mainLayout.setCenter(messageArea);
        mainLayout.setBottom(inputBox);
        
        // Event handlers
        sendButton.setOnAction(e -> sendCommand(commandField.getText()));
        commandField.setOnAction(e -> sendCommand(commandField.getText()));
        listButton.setOnAction(e -> sendCommand("LIST"));
        
        playButton.setOnAction(e -> {
            String selectedVideo = videoSelector.getValue();
            String selectedProtocol = protocolSelector.getValue();
            
            if (selectedVideo != null && selectedProtocol != null) {
                String playCommand;
                
                if ("HLS".equals(selectedProtocol)) {
                    // For HLS, don't specify quality or format
                    playCommand = "PLAY " + selectedVideo + " PROTOCOL=" + selectedProtocol;
                } else {
                    // For other protocols, include quality and format
                    String selectedQuality = qualitySelector.getValue();
                    String selectedFormat = formatSelector.getValue();
                    
                    if (selectedQuality != null && selectedFormat != null) {
                        // Create the formatted filename: videoName-quality.format
                        playCommand = "PLAY " + selectedVideo + "-" + selectedQuality + "." + selectedFormat + 
                                      " PROTOCOL=" + selectedProtocol;
                    } else {
                        showMessage("Please select quality and format");
                        return;
                    }
                }
                
                sendCommand(playCommand);
            }
        });
        
        // Initial state
        playButton.setDisable(true);
        
        // Set up scene
        Scene scene = new Scene(mainLayout, 600, 500);  // Made slightly taller
        primaryStage.setScene(scene);
        primaryStage.show();
        
        // Connect to server
        connectToServer();
        
        // Handle window close
        primaryStage.setOnCloseRequest(e -> disconnect());
    }
    
    private void connectToServer() {
        String host = "localhost";
        int primaryPort = 5060; // Nginx load balancer port
        int fallbackPort = 5058; // Direct server port
        
        try {
            showMessage("Attempting to connect to " + host + ":" + primaryPort + " (load balancer)...");
            socket = new Socket(host, primaryPort);
            input = new DataInputStream(socket.getInputStream());
            output = new DataOutputStream(socket.getOutputStream());
            showMessage("Connected to " + host + ":" + primaryPort);
            
            // Start server communication thread
            new Thread(this::listenForMessages).start();
            
            // Get initial video list
            sendCommand("LIST");
        } catch (IOException e) {
            showMessage("Connection to " + host + ":" + primaryPort + " failed: " + e.getMessage());
            showMessage("Attempting to connect to fallback " + host + ":" + fallbackPort + "...");
            try {
                socket = new Socket(host, fallbackPort);
                input = new DataInputStream(socket.getInputStream());
                output = new DataOutputStream(socket.getOutputStream());
                showMessage("Connected to fallback " + host + ":" + fallbackPort);

                // Start server communication thread
                new Thread(this::listenForMessages).start();
                
                // Get initial video list
                sendCommand("LIST");
            } catch (IOException ex) {
                showMessage("Connection to fallback " + host + ":" + fallbackPort + " also failed: " + ex.getMessage());
                showMessage("Please ensure the server or load balancer is running.");
            }
        }
    }
    
    private void listenForMessages() {
        try {
            while (true) {
                String message = input.readUTF();
                Platform.runLater(() -> handleServerMessage(message));
            }
        } catch (IOException e) {
            if (!socket.isClosed()) {
                Platform.runLater(() -> showMessage("Server connection lost"));
            }
        }
    }
    
    private void sendCommand(String command) {
        try {
            if (command == null || command.trim().isEmpty()) return;
            
            output.writeUTF(command);
            showMessage("Sent: " + command);
            
        } catch (IOException e) {
            showMessage("Send error: " + e.getMessage());
        }
    }
    
    private void handleServerMessage(String message) {
        if (message.startsWith("Available videos:")) {
            showMessage(message);
            updateVideoList(message);
        } else if (message.startsWith("STREAM:")) {
            String[] parts = message.split(":");
            if (parts.length >= 3) {
                int port = Integer.parseInt(parts[1]);
                String filename = parts[2];
                String protocol = parts.length >= 4 ? parts[3] : "UDP"; // Default to UDP
                
                // Extract SDP content if present (for RTP)
                String sdpContent = null;
                if (parts.length >= 6 && "RTP/UDP".equals(protocol) && "SDP".equals(parts[4])) {
                    try {
                        // Decode the Base64 SDP content
                        sdpContent = new String(java.util.Base64.getDecoder().decode(parts[5]));
                        showMessage("Received SDP content for RTP streaming");
                    } catch (Exception e) {
                        showMessage("Error decoding SDP content: " + e.getMessage());
                    }
                }
                // Legacy path-based SDP handling
                else if (parts.length >= 5 && "RTP/UDP".equals(protocol)) {
                    sdpContent = null; // Use the path instead
                    showMessage("Using SDP file path: " + parts[4]);
                }
                
                showMessage("Starting stream: " + filename + " with protocol: " + protocol);
                
                // Wait a bit to ensure server is ready before starting client
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                
                playStream(port, protocol, sdpContent);
            }
        } else {
            showMessage(message);
        }
    }

    private void updateVideoList(String listMessage) {
        videoSelector.getItems().clear();
        videoMetadata.clear();
        
        // Parse the list message
        String[] lines = listMessage.split("\n");
        
        // Process each line after "Available videos:"
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            
            // Parse video name, qualities, and formats
            // Example: "input_fish - Qualities: [1080p, 720p, 360p], Formats: [mp4, avi, mkv]"
            int dashIndex = line.indexOf(" - ");
            if (dashIndex > 0) {
                String videoName = line.substring(0, dashIndex).trim();
                
                // Parse qualities
                List<String> qualities = parseListValues(line, "Qualities: \\[([^\\]]*)\\]");
                
                // Parse formats
                List<String> formats = parseListValues(line, "Formats: \\[([^\\]]*)\\]");
                
                // Store metadata
                videoMetadata.put(videoName, new VideoMetadata(qualities, formats));
                
                // Add video to selector
                videoSelector.getItems().add(videoName);
            }
        }
        
        // Enable controls if we have videos
        boolean hasVideos = !videoSelector.getItems().isEmpty();
        playButton.setDisable(!hasVideos);
        
        if (hasVideos) {
            videoSelector.setValue(videoSelector.getItems().get(0));
            // Trigger the action to populate quality and format selectors
            videoSelector.fireEvent(new javafx.event.ActionEvent());
        }
    }
    
    // Helper method to parse comma-separated values from string using regex
    private List<String> parseListValues(String line, String pattern) {
        List<String> values = new ArrayList<>();
        Pattern p = Pattern.compile(pattern);
        Matcher m = p.matcher(line);
        if (m.find() && m.groupCount() >= 1) {
            String valueList = m.group(1);
            String[] valueArray = valueList.split(",\\s*");
            for (String value : valueArray) {
                values.add(value.trim());
            }
        }
        return values;
    }
    
    // Inner class to store video metadata
    private static class VideoMetadata {
        private final List<String> qualities;
        private final List<String> formats;
        
        public VideoMetadata(List<String> qualities, List<String> formats) {
            this.qualities = qualities;
            this.formats = formats;
        }
        
        public List<String> getQualities() {
            return qualities;
        }
        
        public List<String> getFormats() {
            return formats;
        }
    }
    
    /**
     * Very simple client playback method - minimal parameters
     */
    private void playStream(int port, String protocol, String sdpContent) {
        Platform.runLater(() -> playButton.setDisable(true));
        
        new Thread(() -> {
            try {
                List<String> command = new ArrayList<>();
                command.add("ffplay");
                
                // Simple protocol handling
                if ("RTP/UDP".equals(protocol) && sdpContent != null) {
                    // Create a temporary SDP file
                    File tempSdpFile = File.createTempFile("rtp_stream_", ".sdp");
                    tempSdpFile.deleteOnExit();

                    try (FileWriter writer = new FileWriter(tempSdpFile)) {
                        writer.write(sdpContent);
                    }

                    showMessage("Using SDP file: " + tempSdpFile.getAbsolutePath());
                    command.add("-protocol_whitelist");
                    command.add("file,rtp,udp");

                    // Make sure stats output is visible
                    command.add("-stats");

                    command.add("-i");
                    command.add(tempSdpFile.getAbsolutePath());

                    // Add -autoexit but we'll also use our custom monitor
                    command.add("-autoexit");

                    // Show exact command being run
                    String cmdStr = String.join(" ", command);
                    showMessage("Running: " + cmdStr);

                    ProcessBuilder pb = new ProcessBuilder(command);
                    pb.redirectErrorStream(true);
                    Process playerProcess = pb.start();
                    currentProcess.set(playerProcess);

                    // Launch the empty frame monitor
                    monitorEmptyFramesAndOutput(playerProcess, 50); // 50 empty frames threshold

                    // Continue monitoring output as usual
                    // try (BufferedReader reader = new BufferedReader(
                    //         new InputStreamReader(playerProcess.getInputStream()))) {
                    //     String line;
                    //     while ((line = reader.readLine()) != null) {
                    //         final String output = line;
                    //         Platform.runLater(() -> messageArea.appendText(output + "\n"));
                    //     }
                    // }
                } else {
                    switch (protocol.toUpperCase()) {
                        case "UDP":
                            // Use flags that ffplay actually supports
                            command.add("-fflags");
                            command.add("discardcorrupt+flush_packets"); // Keep discardcorrupt, add flush_packets
                            
                            // These proper options help detect when stream ends
                            command.add("-stats");
                            
                            // Keep good analysis parameters
                            command.add("-probesize");
                            command.add("32768");
                            command.add("-analyzeduration");
                            command.add("2000000");
                            
                            // Better UDP URL with timeout params that ffplay supports
                            command.add("-i");
                            command.add("udp://127.0.0.1:" + port + "?timeout=1000000&fifo_size=5000000");
                            break;
                        case "TCP":
                            command.add("-i");
                            command.add("tcp://127.0.0.1:" + port);
                            break;
                        case "HLS":
                            command.add("-i");
                            command.add("http://127.0.0.1:" + port + "/master.m3u8");
                            break;
                        default:
                            showMessage("Unsupported protocol: " + protocol);
                            return;
                    }
                }
                
                command.add("-autoexit");
                
                // Show exact command being run
                String cmdStr = String.join(" ", command);
                showMessage("Running: " + cmdStr);
                
                ProcessBuilder pb = new ProcessBuilder(command);
                System.out.println(command);
                pb.redirectErrorStream(true);
                Process playerProcess = pb.start();
                currentProcess.set(playerProcess);
                
                // Monitor the process output
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(playerProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        final String output = line;
                        Platform.runLater(() -> messageArea.appendText(output + "\n"));
                    }
                }
                
                int exitCode = playerProcess.waitFor();
                showMessage("Playback ended with code: " + exitCode);
                
            } catch (Exception e) {
                showMessage("Error: " + e.toString() + " - " + e.getMessage());
                e.printStackTrace();
            } finally {
                Platform.runLater(() -> playButton.setDisable(false));
            }
        }).start();
    }

    /**
     * Combined method to monitor empty frames and display output
     */
    private void monitorEmptyFramesAndOutput(Process process, int emptyFrameThreshold) {
        new Thread(() -> {
            try {
                int emptyFrameCount = 0;
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()));
                
                String line;
                while ((line = reader.readLine()) != null) {
                    // Display the line in UI
                    final String output = line;
                    Platform.runLater(() -> messageArea.appendText(output + "\n"));
                    
                    // Check for empty frame
                    if (line.contains("vq=    0KB")) {
                        emptyFrameCount++;
                        
                        if (emptyFrameCount >= emptyFrameThreshold) {
                            showMessage("Detected " + emptyFrameThreshold + 
                                       " consecutive empty frames - closing player");
                            process.destroy();
                            // Stop reading after closing the process
                            break;
                        }
                    } else {
                        // Reset counter if we see a non-empty frame
                        emptyFrameCount = 0;
                    }
                }
            } catch (IOException e) {
                // This is expected when the process ends
            }
        }).start();
    }

    private void showMessage(String message) {
        Platform.runLater(() -> {
            messageArea.appendText(message + "\n");
            // Auto-scroll to bottom
            messageArea.setScrollTop(Double.MAX_VALUE);
        });
    }
    
    private void disconnect() {
        try {
            // Stop any playing video
            Process p = currentProcess.get();
            if (p != null) {
                p.destroyForcibly();
            }
            
            // Close network resources
            if (output != null) output.writeUTF("Bye");
            if (input != null) input.close();
            if (output != null) output.close();
            if (socket != null) socket.close();
        } catch (IOException e) {
            // Ignore close errors
        }
    }
    
    public static void main(String[] args) {
        launch(args);
    }
}