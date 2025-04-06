package com.multisrv;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.github.kokorin.jaffree.StreamType;
import com.github.kokorin.jaffree.ffmpeg.*;
import com.github.kokorin.jaffree.ffmpeg.UrlInput;
import com.github.kokorin.jaffree.ffprobe.*;

/**
 * VideoManager class manages video file indexing, format analysis, and transcoding using FFmpeg.
 */
public class VideoManager {
    // Maps to store video qualities and formats for each video
    private final Map<String, Set<String>> videoQualities = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> videoFormats = new ConcurrentHashMap<>();
    
    // Supported formats and qualities
    private final List<String> supportedFormats = Arrays.asList("mp4", "mkv", "avi");
    private final List<String> supportedQualities = Arrays.asList("144p", "240p", "360p", "480p", "720p", "1080p");
    
    // Active file locks to avoid concurrent transcoding on the same file
    private final Map<String, LockWithChannel> activeLocks = new ConcurrentHashMap<>();

    /**
     * Helper class to store a FileLock along with its channel and RandomAccessFile.
     */
    private static class LockWithChannel {
        final FileLock lock;
        final FileChannel channel;
        final RandomAccessFile raf;

        public LockWithChannel(FileLock lock, FileChannel channel, RandomAccessFile raf) {
            this.lock = lock;
            this.channel = channel;
            this.raf = raf;
        }
    }

    public VideoManager() {
        // Set FFmpeg path (ensure the path is correct on your system)
        FFmpeg.atPath(Paths.get("C:\\Program Files\\ffmpeg\\bin"));
    }
    
    /**
     * Initializes the video manager by indexing existing videos and analyzing missing formats.
     */
    public void initialize() {
        System.out.println("Initializing video manager...");
        File videoDir = getVideoDirectory();
        
        if (videoDir == null) {
            System.err.println("Failed to initialize video manager");
            return;
        }
        
        // First index videos, then analyze and create missing formats, then re-index.
        indexVideoFiles(videoDir);
        analyzeMissingFormats();
        indexVideoFiles(videoDir);
        System.out.println("Video manager initialized successfully");
    }
    
    /**
     * Lists all video files in the given directory and parses them.
     */
    private void indexVideoFiles(File videoDir) {
        File[] files = videoDir.listFiles();
        if (files == null) {
            System.err.println("Error listing files in video directory");
            return;
        }
        
        for (File file : files) {
            if (!file.isFile()) {
                continue;
            }
            parseVideoFile(file.getName());
        }
        
        // Log the indexed videos and their properties
        System.out.println("Indexed videos: " + videoQualities.size());
        for (Map.Entry<String, Set<String>> entry : videoQualities.entrySet()) {
            System.out.println("Video: " + entry.getKey() + 
                               ", Qualities: " + entry.getValue() + 
                               ", Formats: " + videoFormats.get(entry.getKey()));
        }
    }

    /**
     * Returns the video storage directory.
     */
    private File getVideoDirectory() {
        String videoPath = System.getenv("VIDEO_STORAGE_PATH");
        if (videoPath == null) {
            // Default fallback path
            videoPath = "C:\\Users\\perbu\\OneDrive - University of West Attica\\Documents\\8o examino\\Multimedia\\multisrv\\src\\main\\java\\com\\multisrv\\videos";
        }
        
        File videoDir = new File(videoPath);
        
        if (!videoDir.exists()) {
            videoDir.mkdirs();
        }
        
        if (!videoDir.isDirectory()) {
            System.err.println("Video location is not a directory: " + videoDir.getAbsolutePath());
            return null;
        }
        
        return videoDir;
    }

    /**
     * Parses a video file name and indexes its base name, quality, and format.
     * Expected format: videoName-quality.format (e.g., video1-720p.mp4)
     */
    private void parseVideoFile(String fileName) {
        int lastDash = fileName.lastIndexOf('-');
        int lastDot = fileName.lastIndexOf('.');
        
        if (lastDash == -1 || lastDot == -1 || lastDot <= lastDash) {
            return; // Invalid format
        }
        
        String baseName = fileName.substring(0, lastDash);
        String quality = fileName.substring(lastDash + 1, lastDot);
        String format = fileName.substring(lastDot + 1);
        
        // Verify that the quality and format are supported
        if (!supportedQualities.contains(quality) || !supportedFormats.contains(format)) {
            return;
        }
        
        videoQualities.computeIfAbsent(baseName, k -> ConcurrentHashMap.newKeySet()).add(quality);
        videoFormats.computeIfAbsent(baseName, k -> ConcurrentHashMap.newKeySet()).add(format);
    }
    
    /**
     * Returns a formatted list of available videos and their qualities and formats.
     */
    public String getVideoList() {
        StringBuilder list = new StringBuilder("Available videos:\n");
        for (String video : videoQualities.keySet()) {
            list.append(video)
                .append(" - Qualities: ")
                .append(videoQualities.get(video))
                .append(", Formats: ")
                .append(videoFormats.get(video))
                .append("\n");
        }
        return list.toString();
    }
    
    /**
     * Returns detailed information for a specific video.
     */
    public String getVideoInfo(String videoName) {
        if (!videoQualities.containsKey(videoName)) {
            return "Video not found: " + videoName;
        }
        return "Video: " + videoName + 
               "\nAvailable qualities: " + videoQualities.get(videoName) +
               "\nAvailable formats: " + videoFormats.get(videoName);
    }

    /**
     * Tries to acquire a lock for the specified video conversion to prevent duplicate work.
     */
    private boolean acquireLock(String videoName, String quality, String format) {
        String lockKey = videoName + "-" + quality + "." + format;
        File videoDir = getVideoDirectory();
        File lockFile = new File(videoDir, lockKey + ".lock");
        
        try {
            if (!lockFile.exists()) {
                lockFile.createNewFile();
            }
            // Open file without try-with-resources so the stream remains open
            RandomAccessFile raf = new RandomAccessFile(lockFile, "rw");
            FileChannel channel = raf.getChannel();
            FileLock lock = channel.tryLock();
            
            if (lock != null) {
                activeLocks.put(lockKey, new LockWithChannel(lock, channel, raf));
                return true;
            }
            
            // If lock is not available, close resources
            channel.close();
            raf.close();
            return false;
        } catch (IOException e) {
            System.err.println("Error acquiring lock for " + lockKey + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Releases the file lock for the specified video conversion.
     */
    private void releaseLock(String videoName, String quality, String format) {
        String lockKey = videoName + "-" + quality + "." + format;
        LockWithChannel lockWithChannel = activeLocks.remove(lockKey);
        File videoDir = getVideoDirectory();
        File lockFile = new File(videoDir, lockKey + ".lock");
        
        try {
            if (lockWithChannel != null) {
                // Release lock if valid
                try {
                    if (lockWithChannel.lock != null && lockWithChannel.lock.isValid()) {
                        lockWithChannel.lock.release();
                        System.out.println("Lock released for: " + lockKey);
                    }
                } catch (Exception e) {
                    System.err.println("Error releasing file lock: " + e);
                }
                // Close channel
                try {
                    if (lockWithChannel.channel != null && lockWithChannel.channel.isOpen()) {
                        lockWithChannel.channel.close();
                    }
                } catch (Exception e) {
                    System.err.println("Error closing channel: " + e);
                }
                // Close RandomAccessFile
                try {
                    if (lockWithChannel.raf != null) {
                        lockWithChannel.raf.close();
                    }
                } catch (Exception e) {
                    System.err.println("Error closing random access file: " + e);
                }
            }
            
            // Delete the lock file
            if (lockFile.exists()) {
                if (!lockFile.delete()) {
                    System.err.println("Warning: Failed to delete lock file: " + lockFile.getAbsolutePath());
                    lockFile.deleteOnExit();
                } else {
                    System.out.println("Lock file deleted: " + lockFile.getName());
                }
            }
        } catch (Exception e) {
            System.err.println("Error during lock release for " + lockKey + ": " + e);
            e.printStackTrace();
            if (lockFile.exists()) {
                lockFile.deleteOnExit();
            }
        }
    }

    /**
     * Analyzes existing videos and triggers format conversion for any missing quality/format combinations.
     */
    public void analyzeMissingFormats() {
        System.out.println("\nANALYZING MISSING FORMATS THAT COULD BE CREATED WITH FFMPEG:");
        for (String videoName : videoQualities.keySet()) {
            String highestQuality = findHighestQuality(videoQualities.get(videoName));
            int highestQualityIndex = supportedQualities.indexOf(highestQuality);
            
            System.out.println("\nFor video: " + videoName + " (max quality: " + highestQuality + ")");
            for (int i = 0; i <= highestQualityIndex; i++) {
                String quality = supportedQualities.get(i);
                for (String format : supportedFormats) {
                    if (!videoExists(videoName, quality, format)) {
                        System.out.println("  Missing: " + videoName + "-" + quality + "." + format);
                        generateVideoFormat(videoName, quality, format, highestQuality);
                    }
                }
            }
        }
    }

    /**
     * Initiates transcoding to generate a missing video format/quality.
     */
    public void generateVideoFormat(String videoName, String targetQuality, String targetFormat, String sourceQuality) {
        if (videoExists(videoName, targetQuality, targetFormat)) {
            System.out.println("Video already exists: " + videoName + "-" + targetQuality + "." + targetFormat);
            return;
        }
        
        if (!acquireLock(videoName, targetQuality, targetFormat)) {
            System.out.println("Conversion already in progress by another instance: " + 
                              videoName + "-" + targetQuality + "." + targetFormat);
            return;
        }
        
        try {
            File videoDir = getVideoDirectory();
            String sourceFormat = findBestSourceFormat(videoName, sourceQuality);
            
            if (sourceFormat == null) {
                System.err.println("No suitable source format found for " + videoName);
                return;
            }
            
            File sourceFile = new File(videoDir, videoName + "-" + sourceQuality + "." + sourceFormat);
            File targetFile = new File(videoDir, videoName + "-" + targetQuality + "." + targetFormat);
            
            System.out.println("Converting: " + sourceFile.getName() + " -> " + targetFile.getName());
            transcodeFFMPEG(sourceFile, targetFile, targetQuality);
            
            if (targetFile.exists()) {
                System.out.println("Conversion successful: " + targetFile.getName());
                parseVideoFile(targetFile.getName());
            }
        } finally {
            releaseLock(videoName, targetQuality, targetFormat);
        }
    }

    /**
     * Finds the best source format available for a given video quality.
     */
    private String findBestSourceFormat(String videoName, String quality) {
        for (String format : supportedFormats) {
            if (videoExists(videoName, quality, format)) {
                return format;
            }
        }
        return null;
    }

    /**
     * Finds the highest quality available from a set of qualities.
     */
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
    
    /**
     * Checks if a video file exists.
     */
    public boolean videoExists(String videoName, String quality, String format) {
        File videoDir = getVideoDirectory();
        File videoFile = new File(videoDir, videoName + "-" + quality + "." + format);
        return videoFile.exists();
    }
    
    /**
     * Uses FFmpeg via Jaffree to transcode a source video to a target video file.
     */
    private void transcodeFFMPEG(File sourceFile, File targetFile, String targetQuality) {
        try {
            String targetFormat = targetFile.getName().substring(targetFile.getName().lastIndexOf('.') + 1);
            int targetHeight = Integer.parseInt(targetQuality.replace("p", ""));
            
            // Determine bitrate based on quality
            String bitrate;
            switch (targetQuality) {
                case "1080p": bitrate = "5000k"; break;
                case "720p": bitrate = "2500k"; break;
                case "480p": bitrate = "1500k"; break;
                case "360p": bitrate = "1000k"; break;
                case "240p": bitrate = "700k"; break;
                case "144p": bitrate = "400k"; break;
                default: bitrate = "1000k";
            }
            
            // StringBuilder for collecting FFmpeg output (used for error logging)
            final StringBuilder ffmpegOutput = new StringBuilder();
            final AtomicLong duration = new AtomicLong(0);
            
            // Retrieve video duration using FFprobe
            FFprobeResult probeResult = FFprobe.atPath()
                    .setInput(sourceFile.getAbsolutePath())
                    .execute();
            
            if (probeResult.getFormat() != null && probeResult.getFormat().getDuration() != null) {
                duration.set((long)(probeResult.getFormat().getDuration() * 1000));
                System.out.println("Video duration: " + duration.get() + "ms");
            }
            
            // Log the FFmpeg command to be executed
            System.out.println("FFmpeg command: ffmpeg -i " + sourceFile.getAbsolutePath() + 
                               " -vf scale=-2:" + targetHeight + 
                               " -c:v libx264 -b:v " + bitrate + 
                               " -preset medium " + targetFile.getAbsolutePath());
            
            // Build the FFmpeg command
            FFmpeg ffmpeg = FFmpeg.atPath()
                    .addInput(UrlInput.fromPath(sourceFile.toPath()))
                    .setFilter(StreamType.VIDEO, "scale=-2:" + targetHeight)
                    .addArguments("-c:v", "libx264")
                    .addArguments("-b:v", bitrate)
                    .addArguments("-preset", "medium")
                    .setOverwriteOutput(true);
            
            // Configure output settings
            UrlOutput output = UrlOutput.toPath(targetFile.toPath());
            if (targetFormat.equalsIgnoreCase("mp4") || 
                targetFormat.equalsIgnoreCase("mkv") || 
                targetFormat.equalsIgnoreCase("avi")) {
                output.addArguments("-c:a", "aac");
            }
            ffmpeg.addOutput(output);
            
            // Set a progress listener to show conversion progress
            ffmpeg.setProgressListener(new ProgressListener() {
                @Override
                public void onProgress(FFmpegProgress progress) {
                    if (duration.get() > 0) {
                        long percentage = progress.getTimeMillis() * 100 / duration.get();
                        System.out.println("Progress: " + percentage + "% complete");
                    } else {
                        System.out.println("Progress: " + progress.getFrame() + " frames");
                    }
                }
            });
            
            // Set an output listener to log FFmpeg messages to a file
            ffmpeg.setOutputListener(new OutputListener() {
                @Override
                public void onOutput(String line) {
                    ffmpegOutput.append(line).append("\n");
                    try {
                        File logDir = targetFile.getParentFile();
                        File logFile = new File(logDir, "ffmpeg_" + targetFile.getName() + "_" + targetQuality + ".log");
                        try (FileWriter writer = new FileWriter(logFile, true)) {
                            writer.write(line + "\n");
                        }
                    } catch (IOException e) {
                        System.err.println("Error logging to file: " + e.getMessage());
                    }
                }
            });
            
            // Execute the FFmpeg command
            FFmpegResult result = ffmpeg.execute();
            
            // Validate conversion result
            if (targetFile.exists() && targetFile.length() > 0) {
                System.out.println("Conversion complete using Jaffree");
            } else {
                System.err.println("FFmpeg conversion failed (file missing or empty)");
                System.err.println("Full FFmpeg output:");
                System.err.println(ffmpegOutput.toString());
                
                // Delete the corrupted target file if conversion failed
                if (targetFile.exists()) {
                    targetFile.delete();
                    System.err.println("Deleted incomplete output file: " + targetFile.getName());
                }
                
                throw new IOException("FFmpeg conversion failed - see logs for details");
            }
        } catch (Exception e) {
            System.err.println("Error during FFmpeg conversion: " + e.getMessage());
            e.printStackTrace();
            if (targetFile.exists()) {
                targetFile.delete();
            }
        }
    }
}
