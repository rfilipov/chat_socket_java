import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;


public class Peer 
{
    private final int bufferMaxSize = 2000000;
    private int port;
    private String host;
    private String name;
    private Socket socket;
    private Queue<byte[]> Messages = new ConcurrentLinkedQueue<>();
    private Queue<String> Files = new LinkedList<>();
    private Queue<ByteArrayTuple> Chunks = new LinkedList<>();   
    private int chunck_number = 0;

    private final Object FilesLock = new Object();
    private final Object DataLock  = new Object();

    private File lastCreatedFile;
    private int lastFileSize = -1;

    public Peer(int port, String host, String name) 
    {
        this.port = port;
        this.host = host;
        this.name = name;
    }

    public int get_port() { return port; }
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
            sleep(2000);
            startThreads();
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
            sleep(2000);
            startThreads();
        } 
        catch (IOException e) 
        {
            System.err.println("Error connecting to " + targetHost + " on port " + targetPort);
            e.printStackTrace();
        }
    }

    private void startThreads() 
    {
        new Thread(this::listenForData).start();
        new Thread(this::sendingData).start();
        new Thread(this::fillQueue).start();
    }



    public void listenForData() 
    {
        try 
        {

            DataInputStream dataInputStream = new DataInputStream(socket.getInputStream());    
            while (true) 
            {            

                byte header = dataInputStream.readByte();
                System.out.print(header); 
    

                int length = dataInputStream.readInt();
    

                byte[] data = new byte[length];
                dataInputStream.readFully(data);
    
                switch (header) 
                {
                    case 0 -> 
                    {

                        String message = new String(data, StandardCharsets.UTF_8);
                        System.out.println(name + " received message: " + message);
                    }
    
                    case 1 -> 
                    {

                        String fileName = new String(data, StandardCharsets.UTF_8);
                        File file = new File(fileName);
                        if (!file.exists()) 
                        {
                            file.createNewFile();
                        }   
                        lastCreatedFile = file;
                        System.out.println(name + " receiving file: " + fileName);
                    }
    
                    case 2 -> 
                    {
                        if (lastCreatedFile != null)
                        {
                            try (FileOutputStream fos = new FileOutputStream(lastCreatedFile, true))
                            {
                                fos.write(data);
                                System.out.println("Receiving");
                            }
                            catch (IOException e)
                            {
                                System.err.println("Error writing data to file " + lastCreatedFile.getName());
                                e.printStackTrace();
                            }
                        }
                        else
                        {
                            System.err.println("No file created to write data to.");
                        }
                    }
    
                    default -> System.err.println(name + " received unknown header: " + header);
                }
            }
        } 
        catch (IOException e) 
        {
            System.err.println("Error receiving data");
            e.printStackTrace();
        }
    }

    public void sendingData() 
    {
        System.out.println("1");
        try 
        {
            OutputStream outputStream = socket.getOutputStream();

            while (true) 
            {

                System.out.println("2");

                byte[] messageToSend = null;
                ByteArrayTuple chunkToSend = null;


                synchronized (DataLock) 
                {
                    System.out.println("3");

                    while (Messages.isEmpty() && Chunks.isEmpty()) 
                    {
                        System.out.println("4");

                        DataLock.wait();
                    }

                    if (!Messages.isEmpty()) 
                    {
                        System.out.println("5");

                        messageToSend = Messages.poll();
                    } 

                    else if (!Chunks.isEmpty()) 
                    {
                        System.out.println("6");
                        chunkToSend = Chunks.poll();
                    }
                }


                if (messageToSend != null) 
                {

                    byte header = (byte) 0;
                    int msg_length = (int) messageToSend.length;
                    byte[] msg_length_bytes = ByteBuffer.allocate(4).putInt(msg_length).array();
                    
                    outputStream.write(header);
                    outputStream.write(msg_length_bytes);
                    outputStream.write(messageToSend);
                    outputStream.flush();
                } 
                
                else if (chunkToSend != null) 
                {
                    byte header = (byte) chunkToSend.get_type();
                    int msg_length = (int) chunkToSend.getData().length;
                    byte[] msg_length_bytes = ByteBuffer.allocate(4).putInt(msg_length).array();

                    outputStream.write(header);
                    outputStream.write(msg_length_bytes);
                    outputStream.write(chunkToSend.getData());
                    outputStream.flush();
                    
                }
            }
        } 
        catch (IOException | InterruptedException e) 
        {
            System.err.println("Error sending data");
            e.printStackTrace();
        }
    } 

    public void addMsg(String message)
{
    synchronized (DataLock) 
    {
        byte[] message_bytes = message.getBytes(StandardCharsets.UTF_8);
        Messages.add(message_bytes);
        DataLock.notify();
    }
}
    public void addFileForSending(String name)
    {
        synchronized (FilesLock) 
        {    
            System.out.println("InFileSending");
            Files.offer(name);
            FilesLock.notify();   
        }
    }

    public void addChunck(ByteArrayTuple new_)
    {
        synchronized (DataLock) 
        {    
            System.out.println("addChunck");
            Chunks.offer(new_);
            DataLock.notify();
        }
    }

    private int getBuffSize(int file_size)
    {
        return (file_size < bufferMaxSize) ? file_size : bufferMaxSize;
    }

    private void fillQueueDataChuncks(String file_path) 
{
    try (FileInputStream fis = new FileInputStream(file_path)) 
    {
        int bytesRead;
        File file = new File(file_path);
        int fileSize = (int) file.length();
        System.err.println("file size is " + fileSize);
        
        while (fileSize > 0) 
        {
            int buff_size = getBuffSize(fileSize);
            byte[] buffer = new byte[buff_size]; 
            bytesRead = fis.read(buffer, 0, buff_size);
            if (bytesRead == -1) break;
            
            byte[] chunkData = (bytesRead < buff_size) ? Arrays.copyOf(buffer, bytesRead) : buffer;
            System.out.println("Add new chunk fillQueue with size " + bytesRead);
            addChunck(new ByteArrayTuple(chunkData, 2));
            
            fileSize -= bytesRead;
        }
    } 
    catch (IOException e) 
    {
        e.printStackTrace();
    }
}

    public static long getFileSize(String filePath) {
        File file = new File(filePath);
        return file.exists() ? file.length() : -1;
    }

    public static String extractFileName(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return "";
        }

        int lastSlashIndex = filePath.lastIndexOf('/');
        int lastBackslashIndex = filePath.lastIndexOf('\\');
        
        int lastSeparatorIndex = Math.max(lastSlashIndex, lastBackslashIndex);
    
        if (lastSeparatorIndex != -1 && lastSeparatorIndex < filePath.length() - 1) {
            return filePath.substring(lastSeparatorIndex + 1);
        }
        return filePath;
    }

    private void addStartChunk(String file_path) 
    {
        String file_name = extractFileName(file_path);
        byte[] file_name_bytes = file_name.getBytes(StandardCharsets.UTF_8);
        Chunks.add(new ByteArrayTuple(file_name_bytes, 1));
    }

    public void fillQueue()
    {
        while (true)
        {     
            while (Files.isEmpty()) 
            {

                try 
                {
                    synchronized (FilesLock) 
                    {
                        FilesLock.wait();
                    }
                } 
                catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }


            while (!Files.isEmpty())
            {
                String curr_file_path = Files.poll();
                addStartChunk(curr_file_path);
                fillQueueDataChuncks(curr_file_path);
            }
        }
    }

    public void closeConnection() 
    {
        try 
        {
            if (socket != null) socket.close();
        }
        catch (IOException e) 
        {
            System.err.println("Error closing connection.");
            e.printStackTrace();
        }
    }

    public void sleep(int time)
    {
        try 
        {
            Thread.sleep(time);
        } 
        catch (InterruptedException e) 
        {
            System.err.println("Interrupted while waiting for server to start: " + e.getMessage());
            Thread.currentThread().interrupt(); 
            return;
        }
    }

}