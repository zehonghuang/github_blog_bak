import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class ClientDemo {

  private Selector selector;
  private Iterator<SelectionKey> keyIterator;

  public static void main(String[] args) {
    try {
      new ClientDemo().init();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private void init() throws IOException {
    //客户端连接是阻塞的
    SocketChannel sc = SocketChannel.open();
    sc.connect(new InetSocketAddress( "localhost", 16767));
    sc.configureBlocking(false);

    selector = Selector.open();
    sc.register(selector, SelectionKey.OP_READ);

    ByteBuffer buffer = ByteBuffer.wrap(("阿鲁哈阿鲁哈阿鲁哈阿鲁哈阿鲁哈阿鲁哈阿鲁哈阿鲁哈阿鲁哈！！阿鲁哈阿鲁哈阿鲁哈阿鲁哈阿鲁哈阿鲁哈阿鲁哈阿鲁哈阿鲁哈" +
            "阿鲁哈阿鲁哈阿鲁哈阿鲁哈阿鲁哈阿鲁哈阿鲁哈阿鲁哈阿鲁哈阿鲁哈阿鲁哈阿鲁哈阿鲁哈阿鲁哈阿鲁哈阿鲁哈阿" +
            "阿鲁哈阿鲁哈阿鲁哈阿鲁哈阿鲁哈阿鲁哈阿鲁哈阿鲁哈阿鲁哈" +
            "阿鲁哈阿鲁哈阿鲁哈阿鲁哈阿鲁哈阿鲁哈阿鲁哈阿鲁哈阿鲁哈鲁哈阿鲁哈！！！！！哈喽啊！！！！！").getBytes());
    sc.write(buffer);

    listen();
  }

  private void listen() throws IOException {
    while (true) {
      selector.select();
      keyIterator = selector.keys().iterator();
      while (keyIterator.hasNext()) {
        SelectionKey key = keyIterator.next();
        if(key.isReadable()) {
          read(key);//同Server
        }
      }
    }
  }
}
