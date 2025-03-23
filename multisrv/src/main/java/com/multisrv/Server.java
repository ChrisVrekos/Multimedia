package com.multisrv;
import java.io.*; 
import java.net.*; 


public class Server {
        public static void main(String[] args) throws IOException
        {
            int port = args.length > 0 ? Integer.parseInt(args[0]) : 5058;
            try (ServerSocket ss = new ServerSocket(port)) {
                System.out.println("Server is running on port " + port);

                while(true)
                {
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
            }  catch (IOException e) {
                System.err.println("Error starting server: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
    class ClientHandler extends Thread
    {
        final DataInputStream clin;
        final DataOutputStream clout;
        final Socket s;
        public ClientHandler(Socket s, DataInputStream clin, DataOutputStream clout)
        {
            this.s = s;
            this.clin = clin;
            this.clout = clout;
        }
        @Override
        public void run()
        {
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
                    if(received.equalsIgnoreCase("Bye")) 
                {  
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
            try
            { 
                // closing resources 
                this.clin.close(); 
                this.clout.close(); 
                  
            }catch(IOException e){ 
                e.printStackTrace(); 
            } 
    }
      
    }

