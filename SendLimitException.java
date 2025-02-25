public class SendLimitException extends Exception
{
    public SendLimitException() 
    {
        super();
    }

    // Constructor that accepts a message
    public SendLimitException(String message)
    {
        super(message);
    }    
}
