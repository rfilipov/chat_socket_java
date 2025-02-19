/*
 *  The program creates a p2p conection between 2 ussers.
 *  They can send msgs to one another.
 * 
 * 
 */

public class main
{
    public static void main(String[] args) {
       
        Peer p1 = new Peer(4000, "localhost", "Ivan");
        Peer p2 = new Peer(4001, "localhost", "Anna");
        Chat c = new Chat(p1, p2);
        
        c.runChat();
    }
}
