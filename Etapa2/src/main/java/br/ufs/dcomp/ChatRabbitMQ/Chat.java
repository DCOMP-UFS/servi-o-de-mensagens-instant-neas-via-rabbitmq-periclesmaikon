package br.ufs.dcomp.ChatRabbitMQ;

import com.rabbitmq.client.*;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicReference;
import br.ufs.dcomp.ChatRabbitMQ.MensagemOuterClass;
import br.ufs.dcomp.ChatRabbitMQ.MensagemOuterClass.Mensagem;
import br.ufs.dcomp.ChatRabbitMQ.MensagemOuterClass.Conteudo;
import com.google.protobuf.ByteString;

public class Chat {

    public static void main(String[] argv) throws Exception {
        // Configuração do RabbitMQ
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("98.81.164.75"); // Alterar
        factory.setUsername("admin"); // Alterar
        factory.setPassword("password"); // Alterar
        factory.setVirtualHost("/");

        try (Connection connection = factory.newConnection();
             Channel channel = connection.createChannel();
             Scanner scanner = new Scanner(System.in)) {

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
                            // Desserializa a mensagem recebida
                            Mensagem mensagemRecebida = Mensagem.parseFrom(body);
                            String sender = mensagemRecebida.getEmissor();
                            String data = mensagemRecebida.getData();
                            String hora = mensagemRecebida.getHora();
                            String conteudo = mensagemRecebida.getConteudo().getCorpo().toStringUtf8();

                            System.out.println("(" + data + " às " + hora + ") " + sender + " diz: " + conteudo);

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

                // Obtém a data e hora atuais
                String dataAtual = new SimpleDateFormat("dd/MM/yyyy").format(new Date());
                String horaAtual = new SimpleDateFormat("HH:mm").format(new Date());

                // Cria a mensagem usando Protobuf
                Mensagem mensagem = Mensagem.newBuilder()
                        .setEmissor(username)
                        .setData(dataAtual)
                        .setHora(horaAtual)
                        .setGrupo("") // Se não for um grupo, fica vazio
                        .setConteudo(Conteudo.newBuilder()
                                .setTipo("text/plain")
                                .setCorpo(ByteString.copyFromUtf8(input))
                                .build())
                        .build();

                // Serializa para bytes
                byte[] mensagemBytes = mensagem.toByteArray();

                // Envia a mensagem
                channel.basicPublish("", currentTarget.get(), null, mensagemBytes);
            }
        }
    }
}