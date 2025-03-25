package com.multisrv;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class VideoManager {
    private final Map<String, Set<String>> videoQualities = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> videoFormats = new ConcurrentHashMap<>();
    private final List<String> supportedFormats = Arrays.asList("mp4", "wav", "avi");
    private final List<String> supportedQualities = Arrays.asList("144p", "240p", "360p", "480p", "720p", "1080p");
    
    public void initialize() {
        System.out.println("Initializing video manager...");
        File videoDir = getVideoDirectory();
        
        if (videoDir == null) {
            System.err.println("Failed to initialize video manager");
            return;
        }
        
        indexVideoFiles(videoDir);
        
        analyzeMissingFormats();
        // Re-index to include any newly generated files
        indexVideoFiles(videoDir);
        System.out.println("Video manager initialized successfully");

    }
    
    private File getVideoDirectory() {
        File videoDir = new File(System.getProperty("user.dir"), "multisrv/src/main/java/com/multisrv/videos");
        
        if (!videoDir.exists() || !videoDir.isDirectory()) {
            System.err.println("Video directory not found: " + videoDir.getAbsolutePath());
            return null;
        }
        
        return videoDir;
    }
    
    private void indexVideoFiles(File videoDir) {
        File[] files = videoDir.listFiles();
        if (files == null) {
            System.err.println("Error listing files in video directory");
            return;
        }
        
        for (File file : files) {
            if (!file.isFile()) continue;
            
            String fileName = file.getName();
            parseVideoFile(fileName);
        }
        
        // Log the indexed videos
        System.out.println("Indexed videos: " + videoQualities.size());
        for (Map.Entry<String, Set<String>> entry : videoQualities.entrySet()) {
            System.out.println("Video: " + entry.getKey() + 
                               ", Qualities: " + entry.getValue() + 
                               ", Formats: " + videoFormats.get(entry.getKey()));
        }
    }
    
    private void parseVideoFile(String fileName) {
        // Example file name: video1-720p.mp4
        int lastDash = fileName.lastIndexOf('-');
        int lastDot = fileName.lastIndexOf('.');
        
        if (lastDash == -1 || lastDot == -1 || lastDot <= lastDash) {
            return; // Invalid format
        }
        
        String baseName = fileName.substring(0, lastDash);
        String quality = fileName.substring(lastDash + 1, lastDot);
        String format = fileName.substring(lastDot + 1);
        
        // Verify quality and format
        if (!supportedQualities.contains(quality) || !supportedFormats.contains(format)) {
            return;
        }
        
        // Add to our indexed collections
        videoQualities.computeIfAbsent(baseName, k -> ConcurrentHashMap.newKeySet()).add(quality);
        videoFormats.computeIfAbsent(baseName, k -> ConcurrentHashMap.newKeySet()).add(format);
    }
    
    public String getVideoList() {
        StringBuilder list = new StringBuilder("Available videos:\n");
        
        for (String video : videoQualities.keySet()) {
            list.append(video).append(" - Qualities: ")
                .append(videoQualities.get(video))
                .append(", Formats: ")
                .append(videoFormats.get(video))
                .append("\n");
        }
        
        return list.toString();
    }
    
    public String getVideoInfo(String videoName) {
        if (!videoQualities.containsKey(videoName)) {
            return "Video not found: " + videoName;
        }
        
        return "Video: " + videoName + 
               "\nAvailable qualities: " + videoQualities.get(videoName) +
               "\nAvailable formats: " + videoFormats.get(videoName);
    }
    
    public void analyzeMissingFormats() {
        System.out.println("\nANALYZING MISSING FORMATS THAT COULD BE CREATED WITH FFMPEG:");
        
        for (String videoName : videoQualities.keySet()) {
            // Find the highest available quality for this video
            String highestQuality = findHighestQuality(videoQualities.get(videoName));
            int highestQualityIndex = supportedQualities.indexOf(highestQuality);
            
            // Get available formats for this video
            Set<String> availableFormats = videoFormats.get(videoName);
            Set<String> availableQualities = videoQualities.get(videoName);
            
            // Print what combinations are missing
            System.out.println("\nFor video: " + videoName + " (max quality: " + highestQuality + ")");
            
            // Check all possible quality-format combinations that don't exceed the highest quality
            for (int i = 0; i <= highestQualityIndex; i++) {
                String quality = supportedQualities.get(i);
                
                for (String format : supportedFormats) {
                    // If this combination doesn't exist
                    if (!(availableQualities.contains(quality) && availableFormats.contains(format))) {
                        System.out.println("  Missing: " + videoName + "-" + quality + "." + format + 
                                         " (could be created with ffmpeg)");
                    }
                }
            }
        }
    }
    
    private String findHighestQuality(Set<String> qualities) {
        int highestIndex = -1;
        String highestQuality = null;
        
        for (String quality : qualities) {
            int index = supportedQualities.indexOf(quality);
            if (index > highestIndex) {
                highestIndex = index;
                highestQuality = quality;
            }
        }
        
        return highestQuality;
    }
}