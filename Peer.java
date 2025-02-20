import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/*
    notifyAll() is a method that, when called on an object's monitor, wakes up all threads that are waiting on that monitor 
    (i.e. that previously called wait() on that object). Once awakened, those threads will compete to re-acquire the object's lock and resume execution.

    In other words, if multiple threads are waiting (using wait()) on a particular monitor, notifyAll() signals them that something 
    has changed so they can all try to proceed.

    Would you like further details or an example?
 */

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
    private List<File_Object> fileObjects = new ArrayList<>();

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
            new Thread(() -> processFileList()).start();
            
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
            new Thread(() -> processFileList()).start();

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
                final String fileStartMsg = message;  /// my code mabe doesn't work
                if (message.startsWith("TEXT:"))
                {
                    System.out.println(name + " received: " + message.substring(5));
                }
                else if (message.startsWith("FILE_START:"))
                {
                    new Thread(() -> create_file(fileStartMsg)).start();
                    sleep(10); // waits to be sure the file is created so we have somewhere to append
                }
                else if(message.startsWith("Chunk:"))
                {
                    new Thread(() -> fill_file(fileStartMsg)).start(); // not sure but mabe can have a problem, file is not already created and we try to append data in to it
                }
                else
                {
                    System.out.println("EROR INVALID MSG!!!");
                }
            }
        }
        catch (IOException e)
        {
            System.err.println("Error reading messages.");
            e.printStackTrace();
        }
    }

    public void fill_file(String message)
    {
        String[] parts = message.split(":");
        if (parts.length < 3)
        {
            System.out.println("Invalid FILE_CHUNCK header: " + message);
            return;
        }
        
        String file_path = parts[1];
        int size = (int) Long.parseLong(parts[2]);

        byte []buffer = new byte[size];

        try (InputStream inputStream = socket.getInputStream()) 
        {
            int bytesRead = inputStream.read(buffer);

            if (bytesRead != -1) 
            {
                String receivedData = new String(buffer, 0, bytesRead);
                System.out.println("Received: " + receivedData);
            }
        }
        catch (IOException e) 
        { 
            e.printStackTrace();
        }
    }


    public void create_file(String message)
    {
        try
        {
            String[] parts = message.split(":");
            if (parts.length < 3)
            {
                System.out.println("Invalid FILE_START header: " + message);
                return;
            }
            String filename = parts[1];
            long fileSize = Long.parseLong(parts[2]);
            System.out.println(name + " is preparing to receive file: " + filename + " of size " + fileSize);

            FileOutputStream fos = new FileOutputStream(filename);
            fos.close();

        }
        catch (IOException e)
        {
            System.err.println("Error creating file in reciveFiles:");
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

    public void addFileForSending(String filePath, int priority)
    {
        new Thread(() ->
            {
                synchronized (fileTransferLock)
                {
                    File_Object new_File_Object = new File_Object(filePath, priority);

                    int index = 0;
                    while (index < fileObjects.size() && fileObjects.get(index).getPriority() >= priority)
                    {
                        index++;
                    }
                    
                    fileObjects.add(index, new_File_Object);
                }
            }
        ).start();
    }

    public void processFileList()
    {
    
        while (true)
        {
            List<File_Object> highestPriorityFiles = new ArrayList<>();

            synchronized (fileObjects)
            {
                if (fileObjects.isEmpty())
                {
                    try 
                    {
                        fileObjects.wait();
                    }
                    catch (InterruptedException e)
                    {
                        return;
                    }
                }

                int maxPriority = fileObjects.stream()
                                             .mapToInt(File_Object::getPriority)
                                             .max()
                                             .orElse(Integer.MIN_VALUE);

                for (File_Object file : fileObjects)
                {
                    if (file.getPriority() == maxPriority)
                    {
                        highestPriorityFiles.add(file);
                    }
                }
            }

            if (!highestPriorityFiles.isEmpty())
            {

                sendFilesRoundRobin(highestPriorityFiles);


                synchronized (fileObjects)
                {
                    fileObjects.removeAll(highestPriorityFiles); /// remove file after beeing send not like now remove all files after they are all send
                }
            }
        }
    }

    private void sendFilesRoundRobin(List<File_Object> files)
    {

        Map<File_Object, FileInputStream> fileStreams = new HashMap<>();
        Map<File_Object, Integer> remainingBytes = new HashMap<>();

        try 
        {

            for (File_Object file : files)
            {
                File f = new File(file.getFileName());
                if (!f.exists()) continue;

                FileInputStream fis = new FileInputStream(f);
                fileStreams.put(file, fis);
                remainingBytes.put(file, (int) f.length());

                synchronized (outputLock)
                {
                    send.println("FILE_START:" + file.getFileName() + ":" + f.length());
                }
            }

            boolean filesRemaining = true;
            while (filesRemaining)
            {
                filesRemaining = false;

                for (File_Object file : files)
                {
                    if (remainingBytes.get(file) > 0)
                    {

                        synchronized (messageQueueMonitor)
                        {
                            if (!messageQueue.isEmpty())
                            {
                                messageQueueMonitor.notifyAll();
                                sleep(0);
                            }
                        }
                        filesRemaining = true;
                        if(!sendFileChunk(file, fileStreams.get(file), remainingBytes))
                        {
                            
                            synchronized (outputLock)
                            {
                                send.println("FILE_END:" + file.getFileName());
                            }

                            fileStreams.get(file).close();
                        }
                    }
                }
            }
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    private boolean sendFileChunk(File_Object file, FileInputStream fis, Map<File_Object, Integer> remainingBytes) throws IOException
    {
        int bufferSize = getBufferSize(remainingBytes.get(file));
        byte[] buffer = new byte[bufferSize];

        int bytesRead = fis.read(buffer, 0, bufferSize);
        if (bytesRead == -1) return false;

        synchronized (outputLock)
        {
            send.println("CHUNK:" + file.getFileName() + ":" + bytesRead);
            send.flush();

            socket.getOutputStream().write(buffer, 0, bytesRead);
            socket.getOutputStream().flush();
        }

        remainingBytes.put(file, remainingBytes.get(file) - bytesRead);
        
        return true;
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
