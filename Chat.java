import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class Chat 
{   
    private Peer p1, p2;
    
    public Chat(Peer p1, Peer p2)
    {
        this.p1 = p1;
        this.p2 = p2;
    }
    
    public void connectUsers() 
    {

        new Thread(() -> p1.startServer()).start();
        

        try 
        {
            Thread.sleep(1000);
        }
        catch (InterruptedException e) 
        {
            e.printStackTrace();
        }
        

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
                

                if (msg.startsWith("file:"))
                {
                    String filePath = msg.substring(5).trim();
                    sender.addFileForSending(filePath, 0);
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

        connectUsers();
        getUserToSendMsg();
    }


    public void killChat()
    {
        p1.closeConnection();
        p2.closeConnection();
    }
}
