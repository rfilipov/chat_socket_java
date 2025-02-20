import java.io.*;
import java.net.*;
import java.util.LinkedList;
import java.util.Queue;

/*
    The connection established by the code is bidirectional. This means that even though 
    one peer starts as a "server" (accepting the connection) and the other as a "client" 
    (initiating the connection), once the connection is established both peers have an 
    input and an output stream. They can both send and receive data simultaneously over the same socket.
    
    In other words, both peers can send messages to one another; the roles of server and client 
    are only used to establish the connection, but once connected, the communication is full-duplex.
*/

/*
    We use two main locks:
    - outputLock: Ensures that text messages and file data chunks do not interleave.
    - fileTransferLock: Ensures that only one file transfer happens at a time.
    
    Additionally, we use a message queue and a monitor (messageQueueMonitor) to store text messages.
    A dedicated thread processes this queue so that if more than one message is waiting, they are all sent.
*/

public class Peer 
{
    private int port;
    private String host;
    private String name;
    private Socket socket;
    private PrintWriter send;
    private BufferedReader recive;

    // Lock to synchronize output so that messages and file chunks don't interleave.
    private final Object outputLock = new Object();
    // Lock to ensure only one file transfer happens at a time.
    private final Object fileTransferLock = new Object();

    // Queue and monitor for storing outgoing text messages.
    private final Queue<String> messageQueue = new LinkedList<>();
    private final Object messageQueueMonitor = new Object();

    public Peer(int port, String host, String name)
    {
        this.port = port;
        this.host = host;
        this.name = name;
    }
    
    public int get_port()    { return port; }
    public String get_host() { return host; }
    public String get_name() { return name; }
    
    /* 
        Starting a server socket so we can listen for a connection.
        Once a connection is accepted, we start listening for messages and start the message queue processor.
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
            send = new PrintWriter(socket.getOutputStream(), true); // autoflush = true
            recive = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            
            // Start listening for messages in a separate thread.
            new Thread(() -> listenForMessages()).start();
            // Start the message queue processor so that queued messages are sent as soon as possible.
            processMessageQueue();
            
            serverSocket.close();
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
            // Connect as a client
            socket = new Socket(targetHost, targetPort);
            System.out.println(name + " connected to " + targetHost + " on port " + targetPort);
            
            // streams for sending and receiving data
            send = new PrintWriter(socket.getOutputStream(), true);
            recive = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            
            // Start listening for messages in a separate thread.
            new Thread(() -> listenForMessages()).start();
            // Start the message queue processor.
            processMessageQueue();
        }
        catch (IOException e) 
        {
            System.err.println("Error connecting to " + targetHost + " on port " + targetPort);
            e.printStackTrace();
        }
    }
    
    // Continuously read messages from the socket.
    // This runs on a separate thread so we can send and receive concurrently.
    private void listenForMessages()
    {
        try 
        {
            String message;
            while ((message = recive.readLine()) != null)
            {
                // Check if the message is a text message or a file header.
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
                    
                    // Start a new thread for receiving the file so that text messages are not blocked.
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
    
    /* 
        Instead of sending a text message directly, we add it to a queue.
        A separate thread (processMessageQueue) will send all queued messages.
    */
    public void sendMsg(String message)
    {
        synchronized (messageQueueMonitor)
        {
            messageQueue.offer("TEXT:" + message);
            messageQueueMonitor.notifyAll();
        }
        System.out.println(name + " queued text: \"" + message + "\"");
    }
    
    // This thread continually checks the message queue and sends any waiting messages.
    private void processMessageQueue()
    {
        new Thread(() -> 
        {
            while (true)
            {
                String msg = null;
                synchronized (messageQueueMonitor)
                {
                    while (messageQueue.isEmpty())
                    {
                        try 
                        {
                            messageQueueMonitor.wait();
                        }
                        catch (InterruptedException e)
                        {
                            // Exit the thread if interrupted.
                            return;
                        }
                    }
                    msg = messageQueue.poll();
                }
                if (msg != null)
                {
                    synchronized (outputLock)
                    {
                        send.println(msg);
                    }
                }
            }
        }).start();
    }

    // Returns the buffer size: if file size is less than 2MB, use the file size, else use 200KB.
    public int getBufferSize(int file_size)
    {
        return (file_size < 2000000 ? file_size : 200000);
    }

    // A helper sleep method.
    public void sleep(int time)
    {
        try 
        {
            Thread.sleep(time);
        }
        catch (InterruptedException e) 
        {
            e.printStackTrace();
        }
    }

    /* 
        Sends a file in a separate thread.
        Uses a dynamically allocated buffer (allocated only once) and checks if text messages are waiting.
        If there are waiting messages, it yields briefly (using wait/notify) to allow those messages to be sent.
    */
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

                    synchronized (outputLock)
                    {
                        // Send file header with file name and size.
                        send.println("FILE:" + file.getName() + ":" + file.length());
                    }

                    int file_size = (int) file.length();
                    int buffer_size = getBufferSize(file_size);
                    byte[] buffer = new byte[buffer_size];  // Allocate once

                    int bytesRead;
                    while (file_size > 0 && (bytesRead = fileInputStream.read(buffer, 0, Math.min(buffer_size, file_size))) != -1)
                    {
                        // Before sending this chunk, check if there are waiting messages.
                        synchronized (messageQueueMonitor)
                        {
                            if (!messageQueue.isEmpty())
                            {
                                // Yield control briefly to allow queued text messages to be processed.
                                messageQueueMonitor.notifyAll();
                                Thread.sleep(5);  // Wait up to 5ms or until messages are sent.
                            }
                        }
                        
                        synchronized (outputLock)
                        {
                            outStream.write(buffer, 0, bytesRead);
                            outStream.flush(); // Ensure data is sent immediately
                        }

                        file_size -= bytesRead;
                        buffer_size = getBufferSize(file_size);
                        buffer = new byte[buffer_size];  // mabe can be removed

                    }

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

    // Receives a file in a separate thread. Only one file transfer happens at a time due to fileTransferLock.
    private void receiveFile(String filename, long fileSize)
    {
        synchronized (fileTransferLock)
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
                }

                fileOutputStream.close();
                System.out.println(name + " received file: " + filename);
            }
            catch (IOException e)
            {
                System.err.println("Error receiving file: " + filename);
                e.printStackTrace();
            }
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
