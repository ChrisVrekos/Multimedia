package com.multisrv;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.kokorin.jaffree.StreamType;
import com.github.kokorin.jaffree.ffmpeg.*;
import com.github.kokorin.jaffree.ffmpeg.UrlInput;
import com.github.kokorin.jaffree.ffprobe.*;

/**
 * VideoManager class manages video file indexing, format analysis, and transcoding using FFmpeg.
 */
public class VideoManager {
    // Add logger
    private static final Logger logger = LoggerFactory.getLogger(VideoManager.class);
    
    // Maps to store video qualities and formats for each video
    private final Map<String, Set<String>> videoQualities = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> videoFormats = new ConcurrentHashMap<>();
    
    // Supported formats and qualities
    private final List<String> supportedFormats = Arrays.asList("mp4", "mkv", "avi");
    private final List<String> supportedQualities = Arrays.asList("144p", "240p", "360p", "480p", "720p", "1080p");
    
    // Active file locks to avoid concurrent transcoding on the same file
    private final Map<String, LockWithChannel> activeLocks = new ConcurrentHashMap<>();

    // Active streaming processes
    private final Map<String, Process> activeProcesses = new ConcurrentHashMap<>();

    // Last generated SDP content
    private String lastGeneratedSdpContent = null;

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
        logger.info("Initializing video manager...");
        File videoDir = getVideoDirectory();
        
        if (videoDir == null) {
            logger.error("Failed to initialize video manager");
            return;
        }
        
        // First index videos, then analyze and create missing formats
        indexVideoFiles(videoDir);
        analyzeMissingFormats();
        // Generate HLS playlists for all videos
        generateAllHLSPlaylists();
        // Re-index after all operations
        indexVideoFiles(videoDir);
        logger.info("Video manager initialized successfully");
    }

    /**
     * Generates HLS playlists for all videos in the collection
     */
    private void generateAllHLSPlaylists() {
        logger.info("\nGENERATING HLS PLAYLISTS FOR ALL VIDEOS:");
        File videoDir = getVideoDirectory();
        
        for (String videoName : videoQualities.keySet()) {
            try {
                // Skip if HLS already exists
                File hlsDir = new File(videoDir, videoName + "_hls");
                File masterPlaylist = new File(hlsDir, "master.m3u8");
                
                if (masterPlaylist.exists()) {
                    logger.info("HLS playlist already exists for: {}", videoName);
                    continue;
                }
                
                // Create HLS directory structure
                logger.info("Creating HLS playlist for: {}", videoName);
                hlsDir.mkdirs();
                
                // Create quality subdirectories
                Set<String> qualities = videoQualities.get(videoName);
                for (String quality : qualities) {
                    new File(hlsDir, quality).mkdirs();
                }
                
                // Find best source file to use for conversion
                String highestQuality = findHighestQuality(qualities);
                String bestSourceFormat = findBestSourceFormat(videoName, highestQuality);
                
                if (bestSourceFormat == null) {
                    logger.error("No suitable source file found for {} HLS conversion", videoName);
                    continue;
                }
                
                // Source file path
                File sourceFile = new File(videoDir, videoName + "-" + highestQuality + "." + bestSourceFormat);
                if (!sourceFile.exists()) {
                    logger.error("Source file not found: {}", sourceFile.getAbsolutePath());
                    continue;
                }
                
                // Build FFmpeg command
                generateHLSPlaylist(sourceFile, hlsDir, qualities);
                
            } catch (Exception e) {
                logger.error("Error generating HLS playlist for {}: {}", videoName, e.getMessage(), e);
            }
        }
    }

    /**
     * Generates an HLS playlist for a single video with multiple quality levels
     */
    private void generateHLSPlaylist(File sourceFile, File hlsDir, Set<String> qualities) throws IOException {
        // Create directories first
        hlsDir.mkdirs();
        for (String quality : qualities) {
            new File(hlsDir, quality).mkdirs();
        }
        
        // Sort qualities (highest to lowest)
        List<String> sortedQualities = new ArrayList<>(qualities);
        Collections.sort(sortedQualities, (q1, q2) -> {
            int h1 = Integer.parseInt(q1.replace("p", ""));
            int h2 = Integer.parseInt(q2.replace("p", ""));
            return h2 - h1;  // Descending order
        });
        
        // Get the source video duration
        double sourceDuration = getVideoDuration(sourceFile);
        logger.info("Source video duration: {} seconds", sourceDuration);
        
        // Process each quality separately to avoid memory issues
        for (String quality : sortedQualities) {
            try {
                int height = Integer.parseInt(quality.replace("p", ""));
                String bitrate;
                
                // Determine appropriate bitrate based on resolution
                switch (quality) {
                    case "1080p": bitrate = "4500k"; break;
                    case "720p": bitrate = "2500k"; break;
                    case "480p": bitrate = "1000k"; break;
                    case "360p": bitrate = "750k"; break;
                    case "240p": bitrate = "400k"; break;
                    case "144p": bitrate = "250k"; break;
                    default: bitrate = "750k";
                }
                
                File qualityDir = new File(hlsDir, quality);
                
                // Create individual HLS stream for this quality
                List<String> command = new ArrayList<>();
                command.add("ffmpeg");
                command.add("-i");
                command.add(sourceFile.getAbsolutePath());
                
                // Force keyframes every 2 seconds for proper segmentation
                command.add("-force_key_frames");
                command.add("expr:gte(t,n_forced*2)");
                command.add("-sc_threshold");
                command.add("0");
                
                // Video settings
                command.add("-c:v");
                command.add("libx264");
                command.add("-preset");
                command.add("veryfast");
                command.add("-profile:v");
                command.add("main");
                command.add("-crf");
                command.add("23");
                command.add("-maxrate");
                command.add(bitrate);
                command.add("-bufsize");
                command.add(bitrate.replace("k", "000"));
                
                // Proper filter syntax
                command.add("-vf");
                command.add("scale=-2:" + height);
                
                // Audio settings
                command.add("-c:a");
                command.add("aac");
                command.add("-b:a");
                command.add("128k");
                
                // HLS settings - IMPORTANT CHANGES HERE
                command.add("-f");
                command.add("hls");
                command.add("-hls_time");
                command.add("4");  // 4-second segments
                command.add("-hls_list_size");
                command.add("0");  // Include ALL segments in the playlist
                command.add("-hls_flags");
                command.add("split_by_time+independent_segments"); // Removed delete_segments flag
                command.add("-hls_segment_type");
                command.add("mpegts");
                command.add("-hls_segment_filename");
                command.add(new File(qualityDir, "segment_%03d.ts").getAbsolutePath());
                
                // Output file
                command.add(new File(qualityDir, "stream.m3u8").getAbsolutePath());
                
                logger.info("Executing HLS command for quality {}: {}", quality, 
                            String.join(" ", command.subList(0, Math.min(15, command.size()))) + "...");
                
                // Execute FFmpeg process and wait for it to complete
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(true);
                Process process = pb.start();
                
                // Handle process output
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.contains("Error") || line.contains("error")) {
                            logger.error("FFmpeg [{}]: {}", quality, line);
                        } else if (line.contains("time=")) {
                            // Show more progress updates
                            if (Math.random() < 0.1) { // Increased from 0.05 to 0.1
                                logger.info("FFmpeg [{}]: {}", quality, line);
                            }
                        }
                    }
                }
                
                // Wait for process to complete
                int exitCode = process.waitFor();
                logger.info("HLS generation for {} finished with code: {}", quality, exitCode);
                
                // Verify generated segments cover the full duration
                File[] segments = qualityDir.listFiles((dir, name) -> name.endsWith(".ts"));
                if (segments != null) {
                    logger.info("Generated {} segments for quality {}", segments.length, quality);
                }
                
            } catch (Exception e) {
                logger.error("Error creating HLS stream for quality {}: {}", quality, e.getMessage(), e);
            }
        }
        
        // Create the master playlist after all quality streams have been generated
        try {
            StringBuilder masterContent = new StringBuilder();
            masterContent.append("#EXTM3U\n");
            masterContent.append("#EXT-X-VERSION:3\n");
            
            // Add each quality variant
            for (String quality : sortedQualities) {
                int height = Integer.parseInt(quality.replace("p", ""));
                String bitrate;
                
                // Determine bitrate in bits/sec (not kbps)
                switch (quality) {
                    case "1080p": bitrate = "4500000"; break;
                    case "720p": bitrate = "2500000"; break;
                    case "480p": bitrate = "1000000"; break;
                    case "360p": bitrate = "750000"; break;
                    case "240p": bitrate = "400000"; break;
                    case "144p": bitrate = "250000"; break;
                    default: bitrate = "750000";
                }
                
                // Check if this quality's stream exists
                File streamFile = new File(hlsDir, quality + "/stream.m3u8");
                if (streamFile.exists()) {
                    int width = (int)((double)height * 16 / 9); // Assume 16:9 aspect ratio
                    masterContent.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(bitrate)
                               .append(",RESOLUTION=").append(width).append("x").append(height)
                               .append(",NAME=\"").append(quality).append("\"\n");
                    masterContent.append(quality).append("/stream.m3u8\n");
                }
            }
            
            // Write the master playlist
            File masterPlaylist = new File(hlsDir, "master.m3u8");
            try (FileWriter writer = new FileWriter(masterPlaylist)) {
                writer.write(masterContent.toString());
            }
            
            logger.info("Created master playlist at: {}", masterPlaylist.getAbsolutePath());
            
        } catch (Exception e) {
            logger.error("Error creating master playlist: {}", e.getMessage());
        }
    }

    /**
     * Lists all video files in the given directory and parses them.
     */
    private void indexVideoFiles(File videoDir) {
        File[] files = videoDir.listFiles();
        if (files == null) {
            logger.error("Error listing files in video directory");
            return;
        }
        
        for (File file : files) {
            if (!file.isFile()) {
                continue;
            }
            parseVideoFile(file.getName());
        }
        
        // Log the indexed videos and their properties
        logger.info("Indexed videos: {}", videoQualities.size());
        for (Map.Entry<String, Set<String>> entry : videoQualities.entrySet()) {
            logger.info("Video: {}, Qualities: {}, Formats: {}", entry.getKey(), entry.getValue(), videoFormats.get(entry.getKey()));
        }
    }

    /**
     * Returns the video storage directory.
     */
    private File getVideoDirectory() {
        String videoPath = System.getenv("VIDEO_STORAGE_PATH");
        if (videoPath == null) {
            // Default fallback path
            videoPath = "/home/chris/OneDrive/Documents/8o examino/Multimedia/multisrv/src/main/java/com/multisrv/videos";
        }
        
        File videoDir = new File(videoPath);
        
        if (!videoDir.exists()) {
            videoDir.mkdirs();
        }
        
        if (!videoDir.isDirectory()) {
            logger.error("Video location is not a directory: {}", videoDir.getAbsolutePath());
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
            logger.error("Error acquiring lock for {}: {}", lockKey, e.getMessage());
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
                        logger.info("Lock released for: {}", lockKey);
                    }
                } catch (Exception e) {
                    logger.error("Error releasing file lock: {}", e.getMessage());
                }
                // Close channel
                try {
                    if (lockWithChannel.channel != null && lockWithChannel.channel.isOpen()) {
                        lockWithChannel.channel.close();
                    }
                } catch (Exception e) {
                    logger.error("Error closing channel: {}", e.getMessage());
                }
                // Close RandomAccessFile
                try {
                    if (lockWithChannel.raf != null) {
                        lockWithChannel.raf.close();
                    }
                } catch (Exception e) {
                    logger.error("Error closing random access file: {}", e.getMessage());
                }
            }
            
            // Delete the lock file
            if (lockFile.exists()) {
                if (!lockFile.delete()) {
                    logger.warn("Failed to delete lock file: {}", lockFile.getAbsolutePath());
                    lockFile.deleteOnExit();
                } else {
                    logger.info("Lock file deleted: {}", lockFile.getName());
                }
            }
        } catch (Exception e) {
            logger.error("Error during lock release for {}: {}", lockKey, e.getMessage(), e);
            if (lockFile.exists()) {
                lockFile.deleteOnExit();
            }
        }
    }

    /**
     * Analyzes existing videos and triggers format conversion for any missing quality/format combinations.
     */
    public void analyzeMissingFormats() {
        logger.info("\nANALYZING MISSING FORMATS THAT COULD BE CREATED WITH FFMPEG:");
        for (String videoName : videoQualities.keySet()) {
            String highestQuality = findHighestQuality(videoQualities.get(videoName));
            int highestQualityIndex = supportedQualities.indexOf(highestQuality);
            
            logger.info("\nFor video: {} (max quality: {})", videoName, highestQuality);
            for (int i = 0; i <= highestQualityIndex; i++) {
                String quality = supportedQualities.get(i);
                for (String format : supportedFormats) {
                    if (!videoExists(videoName, quality, format)) {
                        logger.info("  Missing: {}-{}.{}", videoName, quality, format);
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
            logger.info("Video already exists: {}-{}.{}", videoName, targetQuality, targetFormat);
            return;
        }
        
        if (!acquireLock(videoName, targetQuality, targetFormat)) {
            logger.info("Conversion already in progress by another instance: {}-{}.{}", 
                        videoName, targetQuality, targetFormat);
            return;
        }
        
        try {
            File videoDir = getVideoDirectory();
            String sourceFormat = findBestSourceFormat(videoName, sourceQuality);
            
            if (sourceFormat == null) {
                logger.error("No suitable source format found for {}", videoName);
                return;
            }
            
            File sourceFile = new File(videoDir, videoName + "-" + sourceQuality + "." + sourceFormat);
            File targetFile = new File(videoDir, videoName + "-" + targetQuality + "." + targetFormat);
            
            logger.info("Converting: {} -> {}", sourceFile.getName(), targetFile.getName());
            transcodeFFMPEG(sourceFile, targetFile, targetQuality);
            
            if (targetFile.exists()) {
                logger.info("Conversion successful: {}", targetFile.getName());
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
                logger.info("Video duration: {}ms", duration.get());
            }
            
            // Log the FFmpeg command to be executed
            logger.info("FFmpeg command: ffmpeg -i {} -vf scale=-2:{} -c:v libx264 -b:v {} -preset medium {}", 
                        sourceFile.getAbsolutePath(), targetHeight, bitrate, targetFile.getAbsolutePath());
            
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
                        logger.info("Progress: {}% complete", percentage);
                    } else {
                        logger.info("Progress: {} frames", progress.getFrame());
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
                        logger.error("Error logging to file: {}", e.getMessage());
                    }
                }
            });
            
            // Execute the FFmpeg command
            FFmpegResult result = ffmpeg.execute();
            
            // Validate conversion result
            if (targetFile.exists() && targetFile.length() > 0) {
                logger.info("Conversion complete using Jaffree");
            } else {
                logger.error("FFmpeg conversion failed (file missing or empty)");
                logger.error("Full FFmpeg output: {}", ffmpegOutput.toString());
                
                // Delete the corrupted target file if conversion failed
                if (targetFile.exists()) {
                    targetFile.delete();
                    logger.error("Deleted incomplete output file: {}", targetFile.getName());
                }
                
                throw new IOException("FFmpeg conversion failed - see logs for details");
            }
        } catch (Exception e) {
            logger.error("Error during FFmpeg conversion: {}", e.getMessage(), e);
            if (targetFile.exists()) {
                targetFile.delete();
            }
        }
    }

    /**
     * Updated playVideo method that handles protocol selection with dynamic ports
     */
    public String playVideo(String videoName, String protocol) {
        try {
            File videoDir = getVideoDirectory();
            
            // Extract base name (videos sent from client might have quality/format)
            String baseName = videoName;
            String quality = null;
            String format = null;
            
            // Parse filename to extract quality and format if present
            int dashIdx = baseName.lastIndexOf('-');
            int dotIdx = baseName.lastIndexOf('.');
            
            if (dashIdx > 0 && dotIdx > dashIdx) {
                quality = baseName.substring(dashIdx + 1, dotIdx);
                format = baseName.substring(dotIdx + 1);
                baseName = baseName.substring(0, dashIdx);
            }
            
            // Allocate a dynamic port for this stream
            int streamPort = allocateFreePort();
            
            // For HLS streaming
            if ("HLS".equalsIgnoreCase(protocol)) {
                // Check for HLS directory
                File hlsDir = new File(videoDir, baseName + "_hls");
                File masterPlaylist = new File(hlsDir, "master.m3u8");
                
                if (!masterPlaylist.exists()) {
                    return "HLS playlist not found for: " + baseName;
                }
                
                // Start HTTP server for HLS content with dynamic port
                startHLSHttpServer(hlsDir, streamPort);
                
                return "STREAM:" + streamPort + ":" + baseName + ":HLS";
            } 
            // For traditional streaming protocols (UDP, TCP, RTP)
            else {
                // Ensure we have the complete file path
                if (quality == null || format == null) {
                    // If quality/format weren't specified, try to find the best available
                    Set<String> qualities = videoQualities.get(baseName);
                    if (qualities == null || qualities.isEmpty()) {
                        return "Video not found: " + baseName;
                    }
                    
                    quality = findHighestQuality(qualities);
                    format = findBestSourceFormat(baseName, quality);
                    
                    if (format == null) {
                        return "No suitable format found for " + baseName;
                    }
                }
                
                File videoFile = new File(videoDir, baseName + "-" + quality + "." + format);
                
                if (!videoFile.exists()) {
                    return "Video file not found: " + videoFile.getName();
                }
                
                // Start streaming based on selected protocol
                switch (protocol.toUpperCase()) {
                    case "TCP":
                        startTCPStream(videoFile.getAbsolutePath(), streamPort);
                        break;
                    case "RTP/UDP":
                        File sdpFile = new File(videoDir, "stream_" + streamPort + ".sdp");
                        startRTPStreamProcess(videoFile, streamPort);
                        
                        // Include the SDP content in the response (not just the path)
                        // Use Base64 encoding to safely transfer
                        try {
                            Thread.sleep(200); // Give the server a small time to generate the SDP
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        
                        if (lastGeneratedSdpContent != null) {
                            return "STREAM:" + streamPort + ":" + videoFile.getName() + ":" + protocol + 
                                   ":SDP:" + java.util.Base64.getEncoder().encodeToString(lastGeneratedSdpContent.getBytes());
                        } else {
                            // Fallback to sending file path
                            return "STREAM:" + streamPort + ":" + videoFile.getName() + ":" + protocol + ":" + sdpFile.getAbsolutePath();
                        }
                    case "UDP":
                        startUDPStream(videoFile.getAbsolutePath(), streamPort);
                        break;
                    default:
                        return "ERROR: Unsupported protocol";
                }
                
                return "STREAM:" + streamPort + ":" + videoFile.getName() + ":" + protocol;
            }
            
        } catch (Exception e) {
            logger.error("Error starting video stream: {}", e.getMessage(), e);
            return "ERROR: Failed to start video stream";
        }
    }

    /**
     * Finds an available port to use for streaming
     */
    private int allocateFreePort() {
        try {
            // Create a server socket on port 0 - system will allocate a free port
            try (ServerSocket socket = new ServerSocket(0)) {
                int port = socket.getLocalPort();
                logger.info("Allocated port: {} for streaming", port);
                return port;
            }
        } catch (IOException e) {
            // If allocation fails, use a random port in a reasonable range
            int port = 40000 + new Random().nextInt(10000); // Between 40000-50000
            logger.info("Using random port: {} (socket allocation failed)", port);
            return port;
        }
    }

    /**
     * Improved TCP streaming process with reliable connection
     */
    private void startTCPStream(String videoFilePath, int port) {
        // Better command with higher quality
        List<String> command = new ArrayList<>();
        command.add("ffmpeg");
        command.add("-re");  // Real-time rate
        command.add("-i");
        command.add(videoFilePath);
        
        // Higher quality encoding
        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add("veryfast");  // Better than ultrafast for quality
        command.add("-crf");
        command.add("23");  // Lower = higher quality (23 is default, 18-23 good quality range)
        command.add("-b:v");
        command.add("3000k");  // Higher bitrate for better quality
        
        // Audio settings
        command.add("-c:a");
        command.add("aac");
        command.add("-b:a");
        command.add("128k");
        
        // Format and output
        command.add("-f");
        command.add("mpegts");
        command.add("tcp://127.0.0.1:" + port + "?listen");
        
        String cmdStr = String.join(" ", command);
        logger.info("Starting TCP stream: {}", cmdStr);
        
        try {
            Process process = new ProcessBuilder(command).start();
            activeProcesses.put("TCP-" + port, process);
            
            // This is crucial for TCP - ensure the server starts listening
            // before we tell the client to connect
            Thread.sleep(1000); // Wait 1 second
            
        } catch (IOException | InterruptedException e) {
            logger.error("Error starting TCP stream: {}", e.getMessage());
        }
    }

    /**
     * Improved UDP streaming process with high quality settings
     */
    private void startUDPStream(String videoFilePath, int port) {
        List<String> command = new ArrayList<>();
        command.add("ffmpeg");
        command.add("-re");
        command.add("-i");
        command.add(videoFilePath);
        
        // CRITICAL: Force keyframe at the very beginning
        command.add("-force_key_frames");
        command.add("expr:gte(t,0)");
        
        // Use simpler encoding with proper headers
        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add("ultrafast");
        
        // CRITICAL: Add bitstream filter for proper H.264 headers
        command.add("-bsf:v");
        command.add("h264_mp4toannexb");
        
        // CRITICAL: Add parameter set inclusion flag
        command.add("-movflags");
        command.add("+export_mvs+faststart");
        
        // Audio settings
        command.add("-c:a");
        command.add("aac");
        command.add("-b:a");
        command.add("128k");
        
        // Format and output with better UDP parameters
        command.add("-f");
        command.add("mpegts");
        command.add("udp://127.0.0.1:" + port);
        
        String cmdStr = String.join(" ", command);
        
        try {
            logger.info("Starting UDP stream: {}", cmdStr);
            Process process = new ProcessBuilder(command).start();
            activeProcesses.put("UDP-" + port, process);
            
            // Give FFmpeg time to start sending data
            Thread.sleep(1000);
        } catch (IOException | InterruptedException e) {
            logger.error("Error starting UDP stream: {}", e.getMessage());
        }
    }

    /**
     * Simplified RTP streaming process
     */
    private void startRTPStreamProcess(File videoFile, int streamPort) throws IOException {
        // Create SDP file path
        File sdpFile = new File(videoFile.getParentFile(), "stream_" + streamPort + ".sdp");
        
        // Build FFmpeg command with proper RTP settings
        List<String> command = new ArrayList<>();
        command.add("ffmpeg");
        command.add("-re");
        command.add("-i");
        command.add(videoFile.getAbsolutePath());
        command.add("-an");
        command.add("-c:v");
        command.add("libx264");  // Use libx264 instead of copy for better compatibility
        command.add("-preset");
        command.add("ultrafast");
        command.add("-tune"); 
        command.add("zerolatency");
        command.add("-f");
        command.add("rtp");
        command.add("-sdp_file");
        command.add(sdpFile.getAbsolutePath());
        command.add("rtp://127.0.0.1:" + streamPort);
        
        logger.info("Starting RTP FFmpeg stream: {}", String.join(" ", command));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        activeProcesses.put("RTP-" + streamPort, process);
        
        // Make sure the SDP file is created before continuing
        int attempts = 0;
        while (!sdpFile.exists() && attempts < 10) {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            attempts++;
        }
        
        // Save SDP content for client response if available
        if (sdpFile.exists()) {
            this.lastGeneratedSdpContent = new String(java.nio.file.Files.readAllBytes(sdpFile.toPath()));
        } else {
            logger.error("SDP file not created: {}", sdpFile.getAbsolutePath());
        }
    }

    /**
     * Start a streaming process and keep track of it
     */
    private Process runStreamProcess(List<String> command) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        
        // Handle the process output in a separate thread
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("error") || line.contains("Error")) {
                        logger.error("FFmpeg: {}", line);
                    } else if (line.contains("frame=") || line.contains("speed=")) {
                        // Log less frequent progress updates
                        if (Math.random() < 0.1) {
                            logger.debug("FFmpeg: {}", line);
                        }
                    } else {
                        logger.debug("FFmpeg: {}", line);
                    }
                }
            } catch (IOException e) {
                logger.error("Error reading FFmpeg output: {}", e.getMessage());
            }
        }).start();
        
        return process;
    }

    /**
     * Starts HTTP server to serve HLS content
     */
    private void startHLSHttpServer(File hlsDir, int port) {
        try {
            // Create HTTP server on the specified port
            com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                    new InetSocketAddress(port), 0);
            
            server.createContext("/", new com.sun.net.httpserver.HttpHandler() {
                @Override
                public void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
                    String requestPath = exchange.getRequestURI().getPath();
                    
                    // Default to master playlist
                    if (requestPath.equals("/") || requestPath.isEmpty()) {
                        requestPath = "/master.m3u8";
                    }
                    
                    // Remove leading slash
                    if (requestPath.startsWith("/")) {
                        requestPath = requestPath.substring(1);
                    }
                    
                    File requestedFile = new File(hlsDir, requestPath);
                    
                    // Security check - ensure file is within HLS directory
                    if (!requestedFile.getCanonicalPath().startsWith(hlsDir.getCanonicalPath())) {
                        logger.error("Security violation: Request outside of HLS directory: {}", requestPath);
                        exchange.sendResponseHeaders(403, -1); // Forbidden
                        exchange.close();
                        return;
                    }
                    
                    if (!requestedFile.exists() || requestedFile.isDirectory()) {
                        logger.error("File not found: {}", requestedFile.getPath());
                        exchange.sendResponseHeaders(404, -1); // Not found
                        exchange.close();
                        return;
                    }
                    
                    // Determine content type
                    String contentType;
                    if (requestPath.endsWith(".m3u8")) {
                        contentType = "application/vnd.apple.mpegurl";
                    } else if (requestPath.endsWith(".ts")) {
                        contentType = "video/mp2t";
                    } else {
                        contentType = "application/octet-stream";
                    }
                    
                    // Add headers for CORS and content type
                    exchange.getResponseHeaders().add("Content-Type", contentType);
                    exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                    
                    // Send the file content
                    exchange.sendResponseHeaders(200, requestedFile.length());
                    try (OutputStream os = exchange.getResponseBody();
                         FileInputStream fis = new FileInputStream(requestedFile)) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = fis.read(buffer)) != -1) {
                            os.write(buffer, 0, bytesRead);
                        }
                    }
                    exchange.close();
                }
            });
            
            // Create a root context that reports server status
            server.createContext("/status", new com.sun.net.httpserver.HttpHandler() {
                @Override
                public void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
                    String response = "HLS Server running on port " + port + "\n" +
                                     "Serving content from: " + hlsDir.getAbsolutePath();
                    exchange.getResponseHeaders().add("Content-Type", "text/plain");
                    exchange.sendResponseHeaders(200, response.length());
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response.getBytes());
                    }
                    exchange.close();
                }
            });
            
            // Use a custom executor with a thread pool for better performance
            server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(10));
            server.start();
            logger.info("HLS HTTP server started on port {} serving {}", port, hlsDir.getName());
            
        } catch (IOException e) {
            logger.error("Error starting HLS server: {}", e.getMessage());
        }
    }

    /**
     * Gets the duration of a video file using FFprobe.
     * @return Duration in seconds, or -1 if duration couldn't be determined
     */
    private double getVideoDuration(File videoFile) {
        try {
            List<String> command = new ArrayList<>();
            command.add("ffprobe");
            command.add("-v");
            command.add("error");
            command.add("-show_entries");
            command.add("format=duration");
            command.add("-of");
            command.add("default=noprint_wrappers=1:nokey=1");
            command.add(videoFile.getAbsolutePath());
            
            ProcessBuilder pb = new ProcessBuilder(command);
            Process process = pb.start();
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String output = reader.readLine();
                if (output != null) {
                    return Double.parseDouble(output);
                }
            }
            
            process.waitFor();
        } catch (Exception e) {
            logger.error("Error getting video duration: {}", e.getMessage());
        }
        return -1;
    }

    /**
     * Cleanup method to terminate all active streaming processes
     */
    public void cleanupStreams() {
        for (Map.Entry<String, Process> entry : activeProcesses.entrySet()) {
            try {
                logger.info("Terminating stream process: {}", entry.getKey());
                entry.getValue().destroy();
            } catch (Exception e) {
                logger.error("Error stopping stream {}: {}", entry.getKey(), e.getMessage());
            }
        }
        activeProcesses.clear();
    }
}
