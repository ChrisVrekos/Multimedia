package com.multisrv;
import java.io.*; 
import java.net.*; 
import java.util.Scanner; 

public class Client { 
    public static void main(String[] args) { 
        try { 
            Scanner scn = new Scanner(System.in);  
            InetAddress ip = InetAddress.getByName("localhost");  

            // Use port 5056 or from command-line args
            int port = args.length > 0 ? Integer.parseInt(args[0]) : 5058;
            Socket s = new Socket(ip, port);  

            DataOutputStream srvout = new DataOutputStream(s.getOutputStream());  

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
            }  

            // Closing resources  
            scn.close();  
            srvout.close();  
            s.close();  
        } catch (Exception e) {  
            e.printStackTrace();  
        }  
    }  
}  