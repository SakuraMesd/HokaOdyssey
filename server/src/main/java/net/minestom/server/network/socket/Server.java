package net.minestom.server.network.socket;

/*
 * ЗАМЕНА класса Minestom 2026.05.17-1.21.11 (тот же адрес net.minestom.server.network.socket.Server; shadow берёт наш
 * первым). Отличие от оригинала ОДНО — в потоке чтения перед циклом пакетов вызывается
 * ink.hoka.odyssey.server.net.ProxyProtocol.preamble (HAProxy PROXY protocol, настоящий IP игрока). Выключено
 * (-Dhoka.proxy.trusted пуст) — это одно чтение volatile, дальше всё как в оригинале.
 * После обновления Minestom: сверить с оригиналом (javap/декомпиляция) и перенести правку.
 */

import java.io.EOFException;
import java.io.IOException;
import java.lang.Thread.Builder.OfVirtual;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;
import net.minestom.server.MinecraftServer;
import net.minestom.server.ServerFlag;
import net.minestom.server.network.packet.PacketVanilla;
import net.minestom.server.network.packet.PacketParser.Client;
import net.minestom.server.network.player.PlayerSocketConnection;
import net.minestom.server.utils.validate.Check;
import org.jetbrains.annotations.ApiStatus.Internal;

public final class Server {
   private volatile boolean stop;
   private final Client packetParser;
   private ServerSocketChannel serverSocket;
   private SocketAddress socketAddress;
   private String address;
   private int port;

   public Server(Client packetParser) {
      this.packetParser = packetParser;
   }

   public Server() {
      this(PacketVanilla.CLIENT_PACKET_PARSER);
   }

   @Internal
   public void init(SocketAddress address) throws IOException {
      ServerSocketChannel server = ServerSocketChannel.open(switch (address) {
         case InetSocketAddress inetSocketAddress -> {
            this.address = inetSocketAddress.getHostString();
            this.port = inetSocketAddress.getPort();
            yield inetSocketAddress.getAddress().getAddress().length == 4 ? StandardProtocolFamily.INET : StandardProtocolFamily.INET6;
         }
         case UnixDomainSocketAddress unixDomainSocketAddress -> {
            this.address = "unix://" + unixDomainSocketAddress.getPath();
            this.port = 0;
            yield StandardProtocolFamily.UNIX;
         }
         default -> throw new IllegalArgumentException("Address must be an InetSocketAddress or a UnixDomainSocketAddress");
      });
      server.bind(address);
      this.serverSocket = server;
      this.socketAddress = address;
      if (address instanceof InetSocketAddress && this.port == 0) {
         this.port = server.socket().getLocalPort();
      }
   }

   @Internal
   public void start() {
      OfVirtual readBuilder = Thread.ofVirtual().name("Ms-Socket-Reader-", 0L);
      OfVirtual writeBuilder = Thread.ofVirtual().name("Ms-Socket-Writer-", 0L);
      Thread.ofVirtual().name("Ms-Socket-Server").start(() -> {
         while (!this.stop) {
            try {
               SocketChannel client = this.serverSocket.accept();
               this.configureSocket(client);
               AtomicReference<PlayerSocketConnection> reference = new AtomicReference<>(null);
               Thread readThread = readBuilder.unstarted(() -> {
                  PlayerSocketConnection conn = reference.get();
                  // Hoka: PROXY-заголовок (только от доверенных) — до первого пакета; false = соединение закрыто
                  if (!ink.hoka.odyssey.server.net.ProxyProtocol.preamble(conn, client)) return;
                  this.playerReadLoop(conn);
               });
               Thread writeThread = writeBuilder.unstarted(() -> this.playerWriteLoop(reference.get()));
               PlayerSocketConnection connection = new PlayerSocketConnection(client, client.getRemoteAddress(), readThread, writeThread);
               reference.set(connection);
               readThread.start();
               writeThread.start();
            } catch (AsynchronousCloseException var8) {
            } catch (IOException e) {
               throw new RuntimeException(e);
            }
         }
      });
   }

   private void configureSocket(SocketChannel channel) throws IOException {
      if (channel.getLocalAddress() instanceof InetSocketAddress) {
         Socket socket = channel.socket();
         socket.setSendBufferSize(ServerFlag.SOCKET_SEND_BUFFER_SIZE);
         socket.setReceiveBufferSize(ServerFlag.SOCKET_RECEIVE_BUFFER_SIZE);
         socket.setTcpNoDelay(ServerFlag.SOCKET_NO_DELAY);
         socket.setSoTimeout(ServerFlag.SOCKET_TIMEOUT);
      }
   }

   private void playerReadLoop(PlayerSocketConnection connection) {
      Check.notNull(connection, "connection cannot be null");

      while (!this.stop) {
         try {
            connection.read(this.packetParser);
         } catch (ClosedChannelException ignored) {
            break;
         } catch (EOFException e) {
            connection.disconnect();
            break;
         } catch (Throwable e) {
            boolean isExpected = e instanceof SocketException && e.getMessage().equals("Connection reset");
            if (!isExpected) {
               MinecraftServer.getExceptionManager().handleException(e);
            }

            connection.disconnect();
            break;
         }
      }
   }

   private void playerWriteLoop(PlayerSocketConnection connection) {
      Check.notNull(connection, "connection cannot be null");

      while (!this.stop) {
         try {
            connection.flushSync();
         } catch (ClosedChannelException ignored) {
            break;
         } catch (EOFException e) {
            connection.disconnect();
            break;
         } catch (Throwable e) {
            boolean isExpected = e instanceof IOException && e.getMessage().equals("Broken pipe");
            if (!isExpected) {
               MinecraftServer.getExceptionManager().handleException(e);
            }

            connection.disconnect();
            break;
         }

         if (!connection.isOnline()) {
            try {
               connection.flushSync();
               connection.getChannel().close();
            } catch (IOException e) {
            }
            break;
         }
      }
   }

   public boolean isOpen() {
      return !this.stop;
   }

   public void stop() {
      this.stop = true;

      try {
         if (this.serverSocket != null) {
            this.serverSocket.close();
         }

         if (this.socketAddress instanceof UnixDomainSocketAddress unixDomainSocketAddress) {
            Files.deleteIfExists(unixDomainSocketAddress.getPath());
         }
      } catch (IOException e) {
         MinecraftServer.getExceptionManager().handleException(e);
      }
   }

   @Internal
   public Client packetParser() {
      return this.packetParser;
   }

   public SocketAddress socketAddress() {
      return this.socketAddress;
   }

   public String getAddress() {
      return this.address;
   }

   public int getPort() {
      return this.port;
   }
}
