import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/// TO DO: searize deserialize, send files while sending msg(msgs have the right ot way), USE ONE SOCKET and many threads

public class Chat 
{   
    private Peer p1, p2;
    
    public Chat(Peer serverPeer, Peer clientPeer) 
    {
        this.p1 = serverPeer;
        this.p2 = clientPeer;
    }
    
    /* 
        we use one of the users to open a server socket that way there is someone listening at the target port 
        then the other user can connect and there will be no error that way

        after the connection is established we can close the server socket because we don't use it for the communication
    */
    public void connectUsers() 
    {
        /// starting the server socket to establish the connection
        new Thread(() -> p1.startServer()).start();
        
        /// sleep the thread to wait for the connection to be established
        try 
        {
            Thread.sleep(1000);
        }
        catch (InterruptedException e) 
        {
            e.printStackTrace();
        }
        
        /// now there is already a serversocket listening so we can connect the other user with their socket 
        p2.startConnection(p1.get_host(), p1.get_port());
    }
    
    public void getUserToSendMsg() 
    {
        BufferedReader r = new BufferedReader(new InputStreamReader(System.in));
        while (true)
        {
            try
            {
                System.out.print("Enter username: ");
                String username = r.readLine();

                System.out.print("Enter message (or file:path to send a file): ");
                String msg = r.readLine();

                /// if the admin types "kill", the chat will close
                if(username.equals("admin") && msg.equals("kill"))
                {
                    System.out.println("Chat connection ended!");
                    killChat();
                    break;
                }
    
                Peer sender = null;
                if (username.equals(p1.get_name()))
                {
                    sender = p1;
                }
                else if (username.equals(p2.get_name()))
                {
                    sender = p2;
                }
                else
                {
                    System.out.println("Unknown username. Please try again.");
                    continue;
                }
                
                // If the message starts with "file:", we treat it as a command to send a file.
                if (msg.startsWith("file:"))
                {
                    String filePath = msg.substring(5).trim();
                    sender.sendFile(filePath);
                }
                else
                {
                    sender.sendMsg(msg);
                }
            }
            catch (IOException e)
            {
                System.err.println("Error reading input: " + e.getMessage());
            }
        }
    }
    
    public void runChat()
    {
        /// after the connection has been made, 2 threads are created for listening for messages for both peers
        connectUsers();
        getUserToSendMsg();
    }

    // when the socket is closed an exception will be thrown that will result in the killing of the thread
    public void killChat()
    {
        p1.closeConnection();
        p2.closeConnection();
    }
}
