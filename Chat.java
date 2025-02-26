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
            Thread.sleep(2000);
        } catch (InterruptedException e) 
        {
            System.err.println("Interrupted while waiting for server to start: " + e.getMessage());
            Thread.currentThread().interrupt(); 
            return;
        }

        new Thread(() -> p2.startConnection(p1.get_host(), p1.get_port())).start();     
    }

    public void getUserToSendMsg() 
    {
        BufferedReader r = new BufferedReader(new InputStreamReader(System.in));
        while (true) 
        {
            try 
            {
                System.out.print("Enter username (" + p1.get_name() + " or " + p2.get_name() + "): ");
                String username = r.readLine();

                if (username.equals("admin")) 
                {
                    System.out.print("Enter command (e.g., 'kill' to end chat): ");
                    String command = r.readLine();
                    if (command.equals("kill")) 
                    {
                        System.out.println("Chat connection ended!");
                        killChat();
                        break;
                    } 
                    else 
                    {
                        System.out.println("Unknown command. Continuing chat...");
                        continue;
                    }
                }

                Peer sender = null;
                if (username.equals(p1.get_name())) 
                    sender = p1;

                else if (username.equals(p2.get_name())) 
                    sender = p2;
                
                else 
                {
                    System.out.println("Unknown username. Please use " + p1.get_name() + " or " + p2.get_name() + ".");
                    continue;
                }

                System.out.print("Enter message (or 'file:<path>' to send a file): ");
                String input = r.readLine();

                if (input.startsWith("file:")) 
                {
                    String[] parts = input.substring(5).split(":");
                    if (parts.length >= 1) 
                    {
                        String filePath = parts[0].trim();
                        sender.addFileForSending(filePath);
                        System.out.println(sender.get_name() + " queued file: " + filePath);
                    } 
                    
                    else 
                    {
                        System.out.println("Invalid file format. Use 'file:<path>:<priority>' (priority optional).");
                    }
                } 
                else 
                {
                    sender.addMsg(input);
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
        //p1.addFileForSending("/Users/rfilipov/Documents/work/pdfs_to_read/prog_lib/[George_Stepanek]_Software_Project_Secrets_Why_So(BookFi).pdf");
        
        p1.addMsg("hello");
        
        killChat();
    }

    public void killChat() {
        p1.closeConnection();
        p2.closeConnection();
    }
}