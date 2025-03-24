package com.multisrv;

import java.io.*;
import java.net.*;
import java.util.ArrayList;

public class Server {
    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 5058;
        try (ServerSocket ss = new ServerSocket(port)) {
            System.out.println("Server is running on port " + port);
            Startup();
            while (true) {
                Socket s = null;
                try {
                    s = ss.accept();
                    System.out.println("A new client connected" + s.toString());

                    DataInputStream clin = new DataInputStream(s.getInputStream());
                    DataOutputStream clout = new DataOutputStream(s.getOutputStream());
                    System.out.println("Detaching client to thread \n");
                    Thread t = new ClientHandler(s, clin, clout);
                    t.start();
                } catch (Exception e) {
                    s.close();
                    e.printStackTrace();
                }
            }
        } catch (IOException e) {
            System.err.println("Error starting server: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void Startup() {
        File f = new File(System.getProperty("user.dir"), "multisrv/src/main/java/com/multisrv/videos");
        String[] files = f.list();
        String path = f.getAbsolutePath();
            // Check if the folder exists and is a directory
        if (!f.exists() || !f.isDirectory()) {
            System.out.println("The 'videos' folder does not exist or is not a directory in path : \n " + path);
            System.exit(1);
        }

        ArrayList < String > videoFiles = new ArrayList < > ();
        ArrayList < String > formats = new ArrayList < > ();
        formats.add("mp4");
        formats.add("wav");
        formats.add("avi");
        ArrayList < String > qualitys = new ArrayList < > ();
        qualitys.add("144p");
        qualitys.add("240p");
        qualitys.add("360p");
        qualitys.add("480p");
        qualitys.add("720p");
        qualitys.add("1080p");
        for (String file: files) {
            int lastDash = file.lastIndexOf("-");
            if (lastDash != -1) { 
                videoFiles.add(file.substring(0, lastDash)); 
            }            }
            for (String file: files) {
            for (String format: formats) {
                for (String quality: qualitys) {
                    if (!file.contains(format) && !file.contains(quality)) {
                        System.out.println("--------------The file combo " + file.split("-")[0] + "-" + quality + "." + format + " does not exist in the videos folder");
                    }
                    else if (file.contains(format) && file.contains(quality)) {
                        System.out.println("The file combo " + file + " exists in the videos folder++++++++++++++");
                }
                else if (file.contains(format) && !file.contains(quality)) {
                    System.out.println("--------------The file combo " + file.split("-")[0] + "-" + quality + "." + format + " does not exist in the videos folder");
                }
                else if (!file.contains(format) && file.contains(quality)) {
                    System.out.println("--------------The file combo " + file.split("-")[0] + "-" + quality + "." + format + " does not exist in the videos folder");
            }
            }
            }
        }

        System.out.println("Files in the videos folder: ");
        for (String video : videoFiles) {
            System.out.println(video);
        }
        System.out.println("Server started successfully");        
    }
}

class ClientHandler extends Thread {
    final DataInputStream clin;
    final DataOutputStream clout;
    final Socket s;

    public ClientHandler(Socket s, DataInputStream clin, DataOutputStream clout) {
        this.s = s;
        this.clin = clin;
        this.clout = clout;
    }

    @Override
    public void run() {
        try {
            clout.writeUTF("Hi Client");
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        String received;
        while (true) {
            try {
                received = clin.readUTF();
                System.out.println(received);
                if (received.equalsIgnoreCase("Bye")) {
                    System.out.println("Client " + this.s + " sends exit...");
                    System.out.println("Closing this connection.");
                    this.s.close();
                    System.out.println("Connection closed");
                    break;
                }
            } catch (SocketException e) {
                System.out.println("Client " + this.s + " disconnected abruptly (SocketException).");
                break; // Exit the loop when the client disconnects unexpectedly
            } catch (EOFException e) {
                System.out.println("Client " + this.s + " disconnected abruptly.");
                break; // Exit the loop when the client disconnects
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        try {
            // closing resources
            this.clin.close();
            this.clout.close();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
