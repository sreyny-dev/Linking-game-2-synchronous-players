package org.example.demo;

import java.io.*;
import java.net.*;
import java.util.*;

public class GameServer {
    private static final int PORT = 4444;

    private static Map<String, Queue<ClientHandler>> waitingPlayers = new HashMap<>();
    private static List<ClientHandler> connectedPlayers = Collections.synchronizedList(new ArrayList<>());


    public static void main(String[] args) {
        System.out.println("Game server started...");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Server is shutting down...");
            synchronized (connectedPlayers) {
                for (ClientHandler player : connectedPlayers) {
                    try {
                        player.out.println("SERVER_SHUTDOWN");
                        player.socket.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }));
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            while (true) {
                ClientHandler clientHandler = new ClientHandler(serverSocket.accept());
                synchronized (connectedPlayers) {
                    connectedPlayers.add(clientHandler);
                }
                clientHandler.start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static synchronized void addPlayerToQueue(ClientHandler player, String boardSize) {
        Queue<ClientHandler> queue = waitingPlayers.computeIfAbsent(boardSize, k -> new LinkedList<>());
        queue.add(player);

        if (queue.size() >= 2) {
            // Match two players and pass boardSize to startGame
            ClientHandler player1 = queue.poll();
            ClientHandler player2 = queue.poll();
            startGame(player1, player2, boardSize);
        }
    }

    private static void startGame(ClientHandler player1, ClientHandler player2, String boardSize) {
        System.out.println("Matching players: " + player1.username + " and " + player2.username);

        // Notify both players about the match
        player1.out.println("MATCH_FOUND");
        player2.out.println("MATCH_FOUND");

        // Generate the game board based on boardSize and send it to both players
        String[] dimensions = boardSize.split("x");
        int rows = Integer.parseInt(dimensions[0]);
        int cols = Integer.parseInt(dimensions[1]);
        int[][] boardGame = generateBoard(rows, cols);

        // Set the game board for both players
        player1.setGame(boardGame, player2);
        player2.setGame(boardGame, player1);

        player1.setTurn(true);
        player2.setTurn(false);

        player1.out.println("YOUR_TURN");
        player2.out.println("NOT_YOUR_TURN");

        // Send the board to both players
        player1.sendBoard(boardGame);
        player2.sendBoard(boardGame);

        System.out.println("Sent MATCH_FOUND and board to both players.");
    }

    private static int[][] generateBoard(int rows, int cols) {
        int[][] board = new int[rows][cols];
        Random random = new Random();
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                board[i][j] = random.nextInt(9);
            }
        }
        return board;
    }

    private static class ClientHandler extends Thread {
        private Socket socket;
        private PrintWriter out;
        private BufferedReader in;
        private String username;
        private String boardSize;
        private ClientHandler opponent;
        private int[][] boardGame;

        private boolean isTurn = false;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        public void setTurn(boolean turn){
            this.isTurn = turn;
            out.println(turn? "YOUR_TURN" : "NOT_YOUR_TURN");// Notify player of their turn status
        }

        public void setGame(int[][] board, ClientHandler opponent) {
            this.boardGame = board;
            this.opponent = opponent;
        }

        public void sendBoard(int[][] board) {
            String boardConfig = convertBoardToString(board); // Convert board to string for sending
            out.println("MATCH_FOUND");
            out.println("BOARD:" + boardConfig);
        }

        public void run() {
            try {
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                out = new PrintWriter(socket.getOutputStream(), true);

                // Read username and board size from client
                username = in.readLine();
                System.out.println(username+ "connected to the server...");

                boardSize = in.readLine(); // e.g., "4x4", "6x8", "8x8"
                System.out.println(username + " selected board size " + boardSize);

                // Add player to matchmaking queue
                addPlayerToQueue(this, boardSize);

                // Wait for further commands or disconnection
                String message;
                while ((message = in.readLine()) != null) {
                    System.out.println(username + " sent: " + message);
                    if (message.equals("disconnect")) {
                        opponent.out.println("opponent_disconnected");
                        break;
                    }

                    if(message.startsWith("REMOVE:")){
                        if(!isTurn){
                            out.println("NOT_YOUR_TURN");
                            continue;
                        }
                        String boardState = message.substring("REMOVE:".length());  // Extract board state
                        String updatedBoard = "UPDATED_BOARD:" + boardState;
                        this.out.println(updatedBoard);
                        opponent.out.println(updatedBoard);
                        passTurnToOpponent(this);
                    }

                    if(message.startsWith("SCORE:")){
                        String score = message.substring("SCORE:".length());
                        this.out.println("YOUR_SCORE:" + score);
                    }

                    if(message.startsWith("GAME_OVER")){
                        this.out.println("YOU_LOSE");
                        opponent.out.println("YOU_WIN");
                        break;
                    }
            }
            } catch (IOException e) {
                System.out.println("Client disconnected: " + socket.getInetAddress().getHostAddress());
            } finally {
                try {
                    socket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        private static String convertBoardToString(int[][] board) {
            StringBuilder sb = new StringBuilder();
            for (int[] row : board) {
                for (int cell : row) {
                    sb.append(cell).append(",");
                }
                sb.append(";"); // Row separator
            }
            return sb.toString();
        }
        private static synchronized void passTurnToOpponent(ClientHandler currentPlayer) {
            currentPlayer.setTurn(false);
            currentPlayer.opponent.setTurn(true); // Pass the turn to the opponent

            currentPlayer.out.println("NOT_YOUR_TURN");
            currentPlayer.opponent.out.println("YOUR_TURN");
        }

    }
}
