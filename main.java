public class main
{
    public static void main(String[] args)
    {
        Peer peer1 = new Peer(12345, "localhost", "Ivan");
        Peer peer2 = new Peer(54321, "localhost", "Anna");
        Chat chat = new Chat(peer1, peer2);
        chat.runChat();
    }
}
