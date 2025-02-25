import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedList;
import java.util.Queue;


public class Peer 
{

    private final int bufferMaxSize = 200000;
    private int port;
    private String host;
    private String name;
    private Socket socket;
    private Queue<String> Files = new LinkedList<>();
    private Deque<byte[]> Messages = new LinkedList<>();
    private Deque<ByteArrayTuple> Chunks = new LinkedList<>();
    private String padding = "!,}{";

    private OutputStream outputStream;

    private final Object FilesLock = new Object();
    private final Object DataLock  = new Object();

    private File lastCreatedFile;

    private byte[] last_data = null;
    private int last_type = -1;

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

    public void printMsg(byte[] data)
    {
        String message = new String(data, StandardCharsets.UTF_8);
        System.out.println(name + " received message: " + message);
    }

    public void createFile(byte[] data) throws IOException
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

    public void writeChunkToFile(byte[] data)
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
 
    public void listenForData() 
    {
        try 
        {
            DataInputStream dataInputStream = new DataInputStream(socket.getInputStream());    
            while (true) 
            {            
                int header_size = padding.getBytes(StandardCharsets.UTF_8).length + 1;
                byte[] header = new byte[header_size];
                dataInputStream.readFully(header);

                int type;
                int recived_type = (int) header[header_size - 1];
                byte[] data;


                if(recived_type == 3)
                {
                    type = last_type;
                    data = last_data;

                    if(type == 0)
                    {
                        synchronized (DataLock) 
                        {
                            Messages.addFirst(data);   
                            DataLock.notify();
                        }
                    }

                    else if(type == 1 || type == 2)
                    {
                        synchronized (DataLock)
                        {
                            Chunks.addFirst(new ByteArrayTuple(last_data, last_type));
                            DataLock.notify();
                        }
                    }

                    else if(type == 3)
                    {
                        System.out.println("\"Looping!!! --------> trying to send header with type 3!!!\"");
                    }

                    else
                    {
                        System.out.println("No such a header!!!");
                    }

                }   
                else
                {
                    type = recived_type;

                    /// read the checksum
                    byte[] checkSum_send = new byte[16]; // 16 is the size of the md5 checksum 
                    dataInputStream.readFully(checkSum_send);
                    System.out.println("SEND CHECKSUM: " + Arrays.toString(checkSum_send));

                    /// read the size of the data we have send 
                    int length = dataInputStream.readInt();

                    /// read the data its self
                    data = new byte[length];
                    dataInputStream.readFully(data);


                    ///generate checkSum for the recived data
                    byte[] checksum_recived = create_md5(data);
                    System.out.println("RECIVED CHECKSUM: " + Arrays.toString(checksum_recived));

                    if(!Arrays.equals(checksum_recived, checkSum_send))
                    {
                        System.err.println("Checksums are diffrent!!!!");

                        throw new Exception("Check sums are different!!!");
                    }

                    switch (type) 
                    {
                        case 0 -> printMsg(data);
                        
                        case 1 -> createFile(data);
        
                        case 2 -> writeChunkToFile(data); 
        
                        default -> System.err.println(name + " received unknown header: " + header);
                    }
                }
            }
        } 

        catch (IOException e) 
        {
            System.err.println("Error receiving data");
            e.printStackTrace();
        }

        catch(Exception e)
        {
            askToResend();
            e.printStackTrace();
        }
    }

    public void askToResend()
    {   
        byte[] header = createHeader(3);
    
        try (OutputStream outputStream = socket.getOutputStream())
        {
            outputStream.write(header);
            outputStream.flush();
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    public void sendDataBlock(byte[] messageToSend,  ByteArrayTuple chunkToSend) throws IOException
    {
        byte type = -1;
        int msg_length = -1;

        if (messageToSend != null) 
        {

            type = (byte) 0;
            msg_length = (int) messageToSend.length;
        } 
        
        else if (chunkToSend != null) 
        {
            type = (byte) chunkToSend.get_type();
            msg_length = (int) chunkToSend.getData().length;
        }

        last_type = type;
        last_data = type == 0 ? messageToSend : chunkToSend.getData();
        
        if(type != -1 && msg_length != -1)
        {
            byte[] msg_length_bytes = ByteBuffer.allocate(4).putInt(msg_length).array();
            byte[] checkSum = type == 0 ? create_md5(messageToSend) : create_md5(chunkToSend.getData());
            byte[] header = createHeader(type);

            outputStream.write(header);
            outputStream.write(checkSum);
            outputStream.write(msg_length_bytes);

            if(type == 0)
                outputStream.write(messageToSend);

            if(type == 1 || type == 2)
                outputStream.write(chunkToSend.getData());

            outputStream.flush();
        }
        else
        {
            
            System.out.println("Error ----------> header: " + type + "msg_length: " + msg_length);
        }
    }

    public void sendingData() 
    {
        try 
        {
            outputStream = socket.getOutputStream();

            while (true) 
            {

                byte[] messageToSend = null;
                ByteArrayTuple chunkToSend = null;


                synchronized (DataLock) 
                {

                    while (Messages.isEmpty() && Chunks.isEmpty()) 
                        DataLock.wait();
                    
                    if (!Messages.isEmpty()) 
                        messageToSend = Messages.pollFirst();
                    
                    else if (!Chunks.isEmpty()) 
                        chunkToSend = Chunks.pollFirst();
                    
                }

                sendDataBlock(messageToSend, chunkToSend);
            }
        } 
        catch (IOException | InterruptedException e) 
        {
            System.err.println("Error sending data");
            e.printStackTrace();
        }
    } 

     public byte[] create_md5(byte[] data)
    {
        byte[] digest = null;

        try
        {
            MessageDigest md = MessageDigest.getInstance("MD5");
            digest = md.digest(data);
        }
        catch (NoSuchAlgorithmException e) 
        {
            System.err.println("MD5 algorithm not found");
         
        }

        System.out.println("size of digest: " + digest.length);
        return digest;
    }

    public byte[] createHeader(int type)
    {
        byte[] padding_bytes = padding.getBytes(StandardCharsets.UTF_8);
        byte[] header = new byte[padding_bytes.length + 1];

        System.arraycopy(padding_bytes, 0, header, 0, padding_bytes.length);
        header[padding_bytes.length] = (byte) type;

        return header;
    }

    public void addMsg(String message)
    {
        synchronized (DataLock) 
        {
            byte[] message_bytes = message.getBytes(StandardCharsets.UTF_8);
            Messages.offerLast(message_bytes);
            DataLock.notify();
        }
    }

    public void addFileForSending(String name)
    {
        synchronized (FilesLock) 
        {    
            Files.offer(name);
            FilesLock.notify();   
        }
    }

    public void addChunck(ByteArrayTuple new_)
    {
        synchronized (DataLock) 
        {    
            System.out.flush();
            Chunks.offerLast(new_);
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