import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

public class File_Object
{
    private String fileName;
    private int priority;
    private RandomAccessFile file;
    private int blockSize;

    public File_Object(String fileName, int priority) 
    {
        this.fileName = fileName;
        this.priority = priority;
        
        try
        {
            this.file = new RandomAccessFile(fileName, "r");
        }
        catch(IOException e)
        {
            System.out.print(e);
        }
        
        int fileSize = (int) new File(fileName).length();
        this.blockSize = getBufferSize(fileSize);
    }

    public String getFileName()
    {
        return fileName;
    }

    public int getPriority()
    {
        return priority;
    }

    public int getBufferSize(int file_size)
    {
        return (file_size < 2000000 ? file_size : 200000);
    }

    public byte[] readNextBlock()
    {
        int bytesRead = -1;
        byte[] buffer = new byte[blockSize];

        try
        {
            bytesRead = file.read(buffer);
        }
        catch(IOException e)
        {
            System.out.print(e);
        }

        if (bytesRead == -1) 
        {
            close();
            return null; // End of file reached
        }

        if (bytesRead < blockSize) 
        {
            byte[] trimmedBuffer = new byte[bytesRead];
            System.arraycopy(buffer, 0, trimmedBuffer, 0, bytesRead);
            return trimmedBuffer;
        }

        return buffer;
    }

    public void close()
    {
        try
        {
            file.close();
        }
        catch(IOException e)
        {
            System.out.println(e);
        }
    }
}
