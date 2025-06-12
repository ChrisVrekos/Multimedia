package com.multisrv;
import java.io.*; 
import java.net.*; 
import java.util.Scanner; 

public class Client { 
    public static void main(String[] args) { 
        try { 
            Scanner scn = new Scanner(System.in);  
            InetAddress ip = InetAddress.getByName("localhost");  

            // Use port 5058 or from command-line args
            int port = args.length > 0 ? Integer.parseInt(args[0]) : 5060;
            Socket s = new Socket(ip, port);  

            // Create both input and output streams
            DataInputStream srvin = new DataInputStream(s.getInputStream());
            DataOutputStream srvout = new DataOutputStream(s.getOutputStream());  

            // Read welcome message
            String welcome = srvin.readUTF();
            System.out.println("Server: " + welcome);

            System.out.println("Connected to server. Type your message:");

            while (true) {  
                System.out.print("> ");  // Prompt user input
                String tosend = scn.nextLine();  

                srvout.writeUTF(tosend);  
                srvout.flush(); // Ensure data is sent immediately  

                if (tosend.equalsIgnoreCase("Bye")) {  
                    System.out.println("Closing connection...");  
                    break;  
                }  
                
                // Read and display server response
                String response = srvin.readUTF();
                System.out.println("Server: " + response);
            }  

            // Closing resources  
            scn.close();  
            srvin.close();
            srvout.close();  
            s.close();  
        } catch (Exception e) {  
            e.printStackTrace();  
        }  
    }  
}