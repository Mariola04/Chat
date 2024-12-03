import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.util.*;
import java.awt.*;
import java.awt.event.*;
import javax.swing.*;

public class ChatClient {

	// Variáveis relacionadas com a interface gráfica --- * NÃO MODIFICAR *
	JFrame frame = new JFrame("Chat Client");
	private JTextField chatBox = new JTextField();
	private JTextArea chatArea = new JTextArea();
	// --- Fim das variáveis relacionadas coma interface gráfica

	// Se for necessário adicionar variáveis ao objecto ChatClient, devem
	// ser colocadas aqui

	// A pre-allocated buffer for the received data
	static private ByteBuffer buffer = ByteBuffer.allocate(16384);
	private SocketChannel sc = null;

	// Decoder for incoming text -- assume UTF-8
	static private final Charset charset = Charset.forName("UTF8");
	static private final CharsetDecoder decoder = charset.newDecoder();

	java.util.List avaliableCommands = Arrays.asList("/join", "/leave", "/bye", "/nick", "/priv");

	// Método a usar para acrescentar uma string à caixa de texto
	// * NÃO MODIFICAR *
	public void printMessage(final String message) {
		chatArea.append(message);
	}

	// Construtor
	public ChatClient(String server, int port) throws IOException {

		// Inicialização da interface gráfica --- * NÃO MODIFICAR *
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		JPanel panel = new JPanel();
		panel.setLayout(new BorderLayout());
		panel.add(chatBox);
		frame.setLayout(new BorderLayout());
		frame.add(panel, BorderLayout.SOUTH);
		frame.add(new JScrollPane(chatArea), BorderLayout.CENTER);
		frame.setSize(500, 300);
		frame.setVisible(true);
		chatArea.setEditable(false);
		chatBox.setEditable(true);
		chatBox.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				try {
					newMessage(chatBox.getText());
				} catch (IOException ex) {
				} finally {
					chatBox.setText("");
				}
			}
		});
		frame.addWindowListener(new WindowAdapter() {
			public void windowOpened(WindowEvent e) {
				chatBox.requestFocusInWindow();
			}
		});
		// --- Fim da inicialização da interface gráfica

		InetSocketAddress sa = new InetSocketAddress(server, port);
		sc = SocketChannel.open(sa);

	}

	// Método invocado sempre que o utilizador insere uma mensagem
	// na caixa de entrada
	public void newMessage(String message) throws IOException {

		buffer.clear();

		// check if it is a command and its valid
		if (message.charAt(0) == '/' && message.charAt(1) != '/') {
			String input[] = message.split(" ", 2);
			if (!avaliableCommands.contains(input[0])) {
				message = "/" + message;
			}
		}

		sc.write(charset.encode(message + '\n'));

	}

	// Método principal do objecto
	public void run() throws IOException {
		while (true) {
			try {
				buffer.clear();
				sc.read(buffer);
				buffer.flip();

				printMessage(processMessage(decoder.decode(buffer).toString()));

			} catch (IOException ie) {
				System.out.println("ERRO CLIENT: " + ie);
			}
		}
	}

	private String processMessage(String message) {
		if (message.length() < 2) {
			return "";
		}
		if (message.charAt(message.length() - 1) == '\n') {
			message = message.substring(0, message.length() - 1);
		}
		String[] MessageSplited = message.split(" ", 3);
		switch (MessageSplited[0]) {
			case "MESSAGE":
				message = MessageSplited[1] + ": " + MessageSplited[2] + "\n";
				break;
			case "JOINED":
				if (MessageSplited.length == 3) {
					message = MessageSplited[1] + " joined " + MessageSplited[2] + "\n";
				} else {
					message = MessageSplited[1] + " joined the room\n";
				}
				break;
			case "NEWNICK":
				message = MessageSplited[1] + " is now called " + MessageSplited[2] + "\n";
				break;
			case "NICK":
				if (MessageSplited[1] == "ERROR") {
					message = "Nickname not available\n";
					break;
				} else {
					message = message + "\n";
					break;
				}
			case "PRIVATE":
				message = "Private message from " + MessageSplited[1] + ": " + MessageSplited[2] + "\n";
				break;
			case "LEFT":
				if (MessageSplited.length == 3) {
					message = MessageSplited[1] + " left " + MessageSplited[2] + "\n";
				} else {
					message = MessageSplited[1] + " left the room\n";
				}
				break;
			case "BYE":
				frame.dispose();
				break;
			default:
				message = message + "\n";
		}
		return message;
	}

	// Instancia o ChatClient e arranca-o invocando o seu método run()
	// * NÃO MODIFICAR *
	public static void main(String[] args) throws IOException {
		ChatClient client = new ChatClient(args[0], Integer.parseInt(args[1]));
		client.run();
	}

}