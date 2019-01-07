import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.*;

public class ServerDemo {

  private Selector selector;
  private Iterator<SelectionKey> keyIterator;

  public static void main(String[] args) {
    try {
      new ServerDemo().init();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  public void init() throws IOException {
    ServerSocketChannel ssc = ServerSocketChannel.open();
    ssc.bind(new InetSocketAddress(16767));
    ssc.configureBlocking(false);

    selector = Selector.open();
    ssc.register(selector, SelectionKey.OP_ACCEPT);

    listen();

  }

  private void listen() throws IOException {
    while (true) {
      System.out.println("listen ...");
      selector.select();
      keyIterator = selector.keys().iterator();
      while (keyIterator.hasNext()) {
        SelectionKey key = keyIterator.next();
        if(key.isAcceptable()) {
          accept(key);
        } else if(key.isReadable()) {
          read(key);
        }
      }
    }
  }

  private void accept(SelectionKey key) throws IOException {
    ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
    SocketChannel sc;
    while ((sc = ssc.accept()) == null) {
      //TODO 超时处理
    }
    System.out.println("appect : " + sc.getRemoteAddress());
    sc.configureBlocking(false);
    sc.register(selector, SelectionKey.OP_READ);
  }

  private void read(SelectionKey key) throws IOException {
    System.out.println("start read ...");
    SocketChannel sc = (SocketChannel) key.channel();
    ByteBuffer buffer = ByteBuffer.allocate(128);
    List<ByteBuffer> byteBuffers = new ArrayList<>();
    int i;
    while ((i = sc.read(buffer)) > 0) {
      System.out.println("reading... len: " + i);
      byteBuffers.add(buffer);
      buffer = ByteBuffer.allocate(128);
    }
    ByteBuffer fullBuffer = merge(byteBuffers);
    System.out.println("client msg: " + new String(fullBuffer.array(), "UTF-8"));

  }

  private ByteBuffer merge(List<ByteBuffer> byteBuffers) {
    int size = 0;
    for(ByteBuffer buffer : byteBuffers) {
      buffer.flip();
      size += buffer.limit();
    }
    ByteBuffer fullBuffer = ByteBuffer.allocate(size);
    for(ByteBuffer buffer : byteBuffers) {
      fullBuffer.put(buffer);
    }
    return fullBuffer;
  }

}
