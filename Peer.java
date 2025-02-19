import java.io.*;
import java.net.*;

/*
    The problem was if there was no server socket ready to listen when the users try to connect the connection fails 
*/

/*
    The connection established by the code is bidirectional. This means that even though 
    one peer starts as a "server" (accepting the connection) and the other as a "client" 
    (initiating the connection), once the connection is established both peers have an 
    input and an output stream. They can both send and receive data simultaneously over the same socket.

    In other words, both peers can send messages to one another; the roles of server and client 
    are only used to establish the connection, but once connected, the communication is full-duplex.
*/

/*
    StartServer function creates a thread for listening for msgs for the first Peer
    StartConnection function creates a thread for listaning for msgs for the second Peer
*/

/*
    Sending: beacouce we need to diferentiat what is text and what file every msg is send with a header(a text in the begining TEXT: FILE:) that way we know what is what
    first we send the file main inforamtion the is it file name of the file and file size separated by :
 
*/


/*
    Why We Don't Need sleep() in receiveFile()
    1️⃣ Reading from a socket is naturally controlled by TCP flow control

    If there’s no data available, inStream.read(buffer) blocks until data arrives.
    This prevents CPU overuse and ensures efficient data transfer.
    2️⃣ Adding sleep() would slow down file reception

    Each time sleep() is called, it pauses the loop, delaying file transfer unnecessarily.
    This could increase file transfer time significantly, especially for large files.
    3️⃣ Unlike sendFile(), we don’t need to give priority to text messages

    In sendFile(), we use sleep() to allow text messages to be sent in between file chunks.
    In receiveFile(), the receiver is just waiting for data, and listenForMessages() is already running in another thread, so text messages are handled separately.
 */

public class Peer 
{
    private int port;
    private String host;
    private String name;
    private Socket socket;
    private PrintWriter send;
    private BufferedReader recive;

    // Lock object to synchronize output so that text messages have priority over file data.
    private final Object outputLock = new Object();
    // Lock object to ensure only one file transfer happens at a time.
    private final Object fileTransferLock = new Object();
    
    public Peer(int port, String host, String name)
    {
        this.port = port;
        this.host = host;
        this.name = name;
    }
    
    public int get_port() { return port; }
    public String get_host() { return host; }
    public String get_name() { return name; }
    
    /* 
        Starting a server socket first so we can listen otherwise the connection can't be established 
        Accepting the connection from the other user
    */  
    public void startServer()
    {
        try 
        {
            ServerSocket serverSocket = new ServerSocket(port);
            System.out.println(name + " listening on port " + port);
            
            // socket is the socket we use to communicate with the other user
            socket = serverSocket.accept();
            System.out.println(name + " accepted connection.");
            
            // streams used for communication through the socket 
            send = new PrintWriter(socket.getOutputStream(), true); // autoflush = true, sending the msgs instantly without waiting to fill the buffer
            recive = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            
            new Thread(() -> listenForMessages()).start();
            
            serverSocket.close(); // after the peers are connected we have no use for the socket and we can close it(that way we use only one socket for communication)
        }
        catch (IOException e)
        {
            System.err.println("Error starting server on port " + port);
            e.printStackTrace();
        }
    }
    
    public void startConnection(String targetHost, int targetPort)
    {
        try 
        {   
            // We have already opened serversocket for listening when we try to connect we connect with no problems
            socket = new Socket(targetHost, targetPort);
            System.out.println(name + " connected to " + targetHost + " on port " + targetPort);
            
            // streams for sending and receiving data
            send = new PrintWriter(socket.getOutputStream(), true);
            recive = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            
            new Thread(() -> listenForMessages()).start();
        }
        catch (IOException e) 
        {
            System.err.println("Error connecting to " + targetHost + " on port " + targetPort);
            e.printStackTrace();
        }
    }
    
    // Continuously read messages from the socket.
    // this code runs on a separate thread so while we listen for incoming msgs we can send as well
    // socket uses TCP, so we can send and receive data at the same time
    private void listenForMessages()
    {
        try 
        {
            String message;
            while ((message = recive.readLine()) != null)
            {
                // Check if the message is a text message or a file header/data
                if (message.startsWith("TEXT:"))
                {
                    System.out.println(name + " received: " + message.substring(5));
                }
                else if (message.startsWith("FILE:"))
                {
                    // Expected header format: FILE:filename:size
                    String[] parts = message.split(":");
                    if (parts.length < 3)
                    {
                        System.out.println("Invalid file header received.");
                        continue;
                    }
                    String filename = parts[1];
                    long fileSize = Long.parseLong(parts[2]);
                    System.out.println(name + " is receiving file: " + filename + " of size " + fileSize);
                    
                    // we create a new thread for reciving the information about the file 
                    // if we don't create a new thread then text msgs will be send but will be deliverd when the file is transferd
                    new Thread(() -> receiveFile(filename, fileSize)).start();
                }
                else
                {
                    System.out.println(name + " received unknown message: " + message);
                }
            }
        }
        catch (IOException e)
        {
            System.err.println("Error reading messages.");
            e.printStackTrace();
        }
    }
    
    public void sendMsg(String message)
    {
        synchronized (outputLock)
        {
            send.println("TEXT:" + message);
            System.out.println(name + " sent: \"" + message + "\"");
        }
    }
    // we need to open a thread for when we send a file , so we can continue to send text msgs with no delay
    public void sendFile(String filePath)
    {
        new Thread(() -> 
        {
            synchronized (fileTransferLock)  // Ensures only ONE file transfer happens at a time
            {
                File file = new File(filePath);
                if (!file.exists())
                {
                    System.err.println("File not found: " + filePath);
                    return;
                }
                try 
                {
                    FileInputStream fileInputStream = new FileInputStream(file);
                    BufferedOutputStream outStream = new BufferedOutputStream(socket.getOutputStream());

                    // Send file header with file name and size.
                    synchronized (outputLock)
                    {
                        send.println("FILE:" + file.getName() + ":" + file.length());
                    }

                    byte[] buffer = new byte[4096];  // 4KB buffer size
                    int bytesRead;
                    while ((bytesRead = fileInputStream.read(buffer)) != -1)
                    {
                        synchronized (outputLock)
                        {
                            outStream.write(buffer, 0, bytesRead);
                            outStream.flush(); // Ensure data is sent immediately
                        }
                    }

                    // Send file end marker
                    synchronized (outputLock)
                    {
                        send.println("FILE_END:" + file.getName());
                    }

                    System.out.println(name + " finished sending file " + file.getName());
                    fileInputStream.close();
                }
                catch (Exception e)
                {
                    System.err.println("Error sending file: " + filePath);
                    e.printStackTrace();
                }
            }
        }).start();
    }

    // we don't need to create a new thread for reciving file, the socket handels that for us we can send and recive at the same time
    private void receiveFile(String filename, long fileSize)
    {
        try 
        {
            FileOutputStream fileOutputStream = new FileOutputStream(filename);
            BufferedInputStream inStream = new BufferedInputStream(socket.getInputStream());

            byte[] buffer = new byte[4096];  // 4KB buffer size
            int bytesRead;
            long remaining = fileSize;

            while (remaining > 0 && (bytesRead = inStream.read(buffer, 0, (int) Math.min(buffer.length, remaining))) != -1)
            {
                fileOutputStream.write(buffer, 0, bytesRead);
                remaining -= bytesRead;
                //======================================================
                // Allow text messages to be recived in between file chunks
                Thread.sleep(50);
                //======================================================
            }

            fileOutputStream.close();
            System.out.println(name + " received file: " + filename);
        }
        catch (IOException e)
        {
            System.err.println("Error receiving file: " + filename);
            e.printStackTrace();
        } 
        catch (InterruptedException e) 
        {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    public void closeConnection()
    {
        try 
        {
            if (socket != null) socket.close();
            if (send != null) send.close();
            if (recive != null) recive.close();
            
            System.out.println(name + " closed connection.");
        }
        catch (IOException e)
        {
            System.err.println("Error closing connection.");
            e.printStackTrace();
        }
    }
}
