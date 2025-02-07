package br.ufs.dcomp.ChatRabbitMQ;

import com.rabbitmq.client.*;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicReference;

public class Chat {

    public static void main(String[] argv) throws Exception {
        // Configuração do RabbitMQ
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("54.234.24.152"); // Alterar
        factory.setUsername("admin"); // Alterar
        factory.setPassword("password"); // Alterar
        factory.setVirtualHost("/");

        Connection connection = factory.newConnection();
        Channel channel = connection.createChannel();

        Scanner scanner = new Scanner(System.in);

        // Solicita o nome do usuário
        System.out.print("User: ");
        String username = scanner.nextLine();

        // Cria uma fila específica para o usuário
        String userQueue = username;
        channel.queueDeclare(userQueue, false, false, false, null);

        System.out.println("Bem-vindo, " + username + "! Você pode começar a enviar mensagens.\n");

        // Variável para armazenar o destinatário atual
        AtomicReference<String> currentTarget = new AtomicReference<>("");

        // Thread para consumir mensagens recebidas
        new Thread(() -> {
            try {
                Consumer consumer = new DefaultConsumer(channel) {
                    @Override
                    public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) throws IOException {
                        String message = new String(body, "UTF-8");
                        String sender = properties.getHeaders() != null && properties.getHeaders().containsKey("sender")
                                ? properties.getHeaders().get("sender").toString()
                                : "Desconhecido";

                        String timestamp = new SimpleDateFormat("dd/MM/yyyy 'às' HH:mm").format(new Date());
                        System.out.println("(" + timestamp + ") " + sender + " diz: " + message);

                        // Reexibe o prompt atual
                        synchronized (System.out) {
                            System.out.print(currentTarget.get().isEmpty() ? ">> " : "@" + currentTarget.get() + ">> ");
                        }
                    }
                };
                channel.basicConsume(userQueue, true, consumer);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();

        // Loop para enviar mensagens
        while (true) {
            if (!currentTarget.get().isEmpty()) {
                System.out.print("@" + currentTarget.get() + ">> ");
            } else {
                System.out.print(">> ");
            }

            String input = scanner.nextLine();

            // Comando para mudar o destinatário
            if (input.startsWith("@")) {
                currentTarget.set(input.substring(1).trim());
                System.out.println("Destinatário alterado para: " + currentTarget.get());
                continue;
            }

            // Encerra o chat
            if (input.equalsIgnoreCase("exit")) {
                System.out.println("Encerrando o chat...");
                break;
            }

            // Verifica se há um destinatário definido
            if (currentTarget.get().isEmpty()) {
                System.out.println("Por favor, defina um destinatário usando @nome_do_usuario.");
                continue;
            }

            // Envia a mensagem para o destinatário atual
            AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                    .headers(Map.of("sender", username))
                    .build();

            channel.basicPublish("", currentTarget.get(), props, input.getBytes("UTF-8"));
        }

        // Fecha recursos
        channel.close();
        connection.close();
        scanner.close();
    }
}
