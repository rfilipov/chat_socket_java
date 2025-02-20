import java.io.*;
import java.net.*;
import java.util.LinkedList;
import java.util.Queue;


public class Peer 
{
    private int port;
    private String host;
    private String name;
    private Socket socket;
    private PrintWriter send;
    private BufferedReader recive;

    private final Object outputLock = new Object();
    private final Object fileTransferLock = new Object();

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
    
    public void startServer()
    {
        try 
        {
            ServerSocket serverSocket = new ServerSocket(port);
            System.out.println(name + " listening on port " + port);
            
            socket = serverSocket.accept();
            System.out.println(name + " accepted connection.");
            
            send = new PrintWriter(socket.getOutputStream(), true); // autoflush = true
            recive = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            
            new Thread(() -> listenForMessages()).start();
            new Thread(() -> processMessageQueue()).start();
            
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
            socket = new Socket(targetHost, targetPort);
            System.out.println(name + " connected to " + targetHost + " on port " + targetPort);
            
            send = new PrintWriter(socket.getOutputStream(), true);
            recive = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            
            new Thread(() -> listenForMessages()).start(); 
            new Thread(() -> processMessageQueue()).start();
        }
        catch (IOException e) 
        {
            System.err.println("Error connecting to " + targetHost + " on port " + targetPort);
            e.printStackTrace();
        }
    }
    
    private void listenForMessages()
    {
        try 
        {
            String message;
            while ((message = recive.readLine()) != null)
            {
                if (message.startsWith("TEXT:"))
                {
                    System.out.println(name + " received: " + message.substring(5));
                }
                else if (message.startsWith("FILE_START:"))
                {
                    String[] parts = message.split(":");
                    if (parts.length < 3)
                    {
                        System.out.println("Invalid file start header received.");
                        continue;
                    }

                    String filename = parts[1];
                    long fileSize = Long.parseLong(parts[2]);
                    System.out.println(name + " is about to receive file: " + filename + " of size " + fileSize);
                    new Thread(() -> receiveFile(filename, fileSize)).start();
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
        synchronized (messageQueueMonitor)
        {
            messageQueue.offer("TEXT:" + message);
            messageQueueMonitor.notifyAll();
        }
        System.out.println(name + " queued text: \"" + message + "\"");
    }
    
    private void processMessageQueue()
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
    
    }

    public int getBufferSize(int file_size)
    {
        return (file_size < 2000000 ? file_size : 200000);
    }

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

    public void sendFile(String filePath)
    {
        new Thread(() -> 
        {
            synchronized (fileTransferLock)
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
                        send.println("FILE_START:" + file.getName() + ":" + file.length());
                    }

                 
                    int remaining = (int) file.length();
                    int buffer_size = getBufferSize(remaining);
                    byte[] buffer = new byte[buffer_size];

                    int bytesRead;
                    while (remaining > 0 && (bytesRead = fileInputStream.read(buffer, 0, Math.min(buffer_size, remaining))) != -1)
                    {   
                        synchronized (outputLock)
                        {
                            outStream.write(buffer, 0, bytesRead);
                            outStream.flush();
                        }
                        
                        remaining -= bytesRead;
                        buffer_size = getBufferSize(remaining);
                        buffer = new byte[buffer_size];
                        
                        synchronized (messageQueueMonitor)
                        {
                            if (!messageQueue.isEmpty())
                            {
                                messageQueueMonitor.notifyAll();
                                sleep(0);
                            }
                        }
                        
                        sleep(0);
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

    private void receiveFile(String filename, long fileSize)
    {
        synchronized (fileTransferLock)
        {
            try 
            {
                FileOutputStream fileOutputStream = new FileOutputStream(filename);
                BufferedInputStream inStream = new BufferedInputStream(socket.getInputStream());
    
                int remaining = (int) fileSize;
                while (remaining > 0)
                {
                    int currentBufferSize = getBufferSize(remaining);
                    byte[] buffer = new byte[currentBufferSize];
    
                    int bytesRead = inStream.read(buffer, 0, currentBufferSize);
                    if (bytesRead == -1)
                    {
                        break;
                    }
    
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
