package br.ufs.dcomp.ChatRabbitMQ;

import com.rabbitmq.client.*;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import br.ufs.dcomp.ChatRabbitMQ.MensagemOuterClass.Mensagem;
import br.ufs.dcomp.ChatRabbitMQ.MensagemOuterClass.Conteudo;
import com.google.protobuf.ByteString;

public class Chat {
    private static final String EXCHANGE_TYPE_FANOUT = "fanout";
    private static volatile boolean running = true;
    private static Connection connection;
    private static Channel channel;
    
    private static Channel createChannel() throws Exception {
        if (connection == null || !connection.isOpen()) {
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost("54.164.1.114");
            factory.setUsername("admin");
            factory.setPassword("password");
            factory.setVirtualHost("/");
            connection = factory.newConnection();
        }
        return connection.createChannel();
    }

    private static boolean checkUserExists(String username) {
        try {
            Channel tempChannel = createChannel();
            tempChannel.queueDeclarePassive(username);
            tempChannel.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean checkGroupExists(String groupName) {
        try {
            Channel tempChannel = createChannel();
            tempChannel.exchangeDeclarePassive(groupName);
            tempChannel.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void main(String[] argv) throws Exception {
        try (Scanner scanner = new Scanner(System.in)) {
            System.out.print("User: ");
            String username = scanner.nextLine();
            
            channel = createChannel();
            String userQueue = username;
            
            // Declarar fila do usuário
            channel.queueDeclare(userQueue, false, false, false, null);

            System.out.println("Bem-vindo, " + username + "! Você pode começar a enviar mensagens.\n");
            System.out.println("Comandos disponíveis:");
            System.out.println("- !addGroup <nomeGrupo> : Criar novo grupo");
            System.out.println("- !addUser <nomeUsuario> <nomeGrupo> : Adicionar usuário ao grupo");
            System.out.println("- !delFromGroup <nomeUsuario> <nomeGrupo> : Remover usuário do grupo");
            System.out.println("- !removeGroup <nomeGrupo> : Excluir grupo");
            System.out.println("- !exit : Sair do chat");
            System.out.println("- #<nomeGrupo> : Entrar em modo de grupo");
            System.out.println("- @<nomeUsuario> : Conversar com usuário\n");

            AtomicReference<String> currentTarget = new AtomicReference<>("");
            AtomicReference<Boolean> isGroup = new AtomicReference<>(false);

            // Thread para consumir mensagens
            Thread consumerThread = new Thread(() -> {
                try {
                    Consumer consumer = new DefaultConsumer(channel) {
                        @Override
                        public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) throws IOException {
                            if (!running) return;
                            
                            Mensagem mensagemRecebida = Mensagem.parseFrom(body);
                            String sender = mensagemRecebida.getEmissor();
                            String data = mensagemRecebida.getData();
                            String hora = mensagemRecebida.getHora();
                            String grupo = mensagemRecebida.getGrupo();
                            String conteudo = mensagemRecebida.getConteudo().getCorpo().toStringUtf8();

                            String displayMessage;
                            if (!grupo.isEmpty()) {
                                displayMessage = String.format("(%s às %s) %s#%s diz: %s", data, hora, sender, grupo, conteudo);
                            } else {
                                displayMessage = String.format("(%s às %s) %s diz: %s", data, hora, sender, conteudo);
                            }

                            System.out.println(displayMessage);

                            synchronized (System.out) {
                                if (isGroup.get()) {
                                    System.out.print("#" + currentTarget.get() + ">> ");
                                } else {
                                    System.out.print(currentTarget.get().isEmpty() ? ">> " : "@" + currentTarget.get() + ">> ");
                                }
                            }
                        }
                    };
                    channel.basicConsume(userQueue, true, consumer);
                } catch (IOException e) {
                    if (running) {
                        e.printStackTrace();
                    }
                }
            });
            consumerThread.start();

            while (running) {
                if (isGroup.get()) {
                    System.out.print("#" + currentTarget.get() + ">> ");
                } else if (!currentTarget.get().isEmpty()) {
                    System.out.print("@" + currentTarget.get() + ">> ");
                } else {
                    System.out.print(">> ");
                }

                String input = scanner.nextLine();

                if (input.startsWith("!")) {
                    if (input.equalsIgnoreCase("!exit")) {
                        System.out.println("Encerrando o chat...");
                        running = false;
                        break;
                    }
                    handleCommand(input, username, currentTarget);
                    continue;
                }

                if (input.startsWith("#")) {
                    String groupName = input.substring(1).trim();
                    if (checkGroupExists(groupName)) {
                        currentTarget.set(groupName);
                        isGroup.set(true);
                        System.out.println("Entrando no grupo: " + groupName);
                    } else {
                        System.out.println("Erro: O grupo '" + groupName + "' não existe!");
                    }
                    continue;
                }

                if (input.startsWith("@")) {
                    String targetUser = input.substring(1).trim();
                    if (checkUserExists(targetUser)) {
                        currentTarget.set(targetUser);
                        isGroup.set(false);
                        System.out.println("Iniciando conversa com: " + targetUser);
                    } else {
                        System.out.println("Erro: O usuário '" + targetUser + "' não existe!");
                    }
                    continue;
                }

                if (currentTarget.get().isEmpty()) {
                    System.out.println("Por favor, defina um destinatário usando @nome_do_usuario ou #nome_do_grupo");
                    continue;
                }

                try {
                    if (!channel.isOpen()) {
                        channel = createChannel();
                    }

                    String dataAtual = new SimpleDateFormat("dd/MM/yyyy").format(new Date());
                    String horaAtual = new SimpleDateFormat("HH:mm").format(new Date());

                    Mensagem mensagem = Mensagem.newBuilder()
                            .setEmissor(username)
                            .setData(dataAtual)
                            .setHora(horaAtual)
                            .setGrupo(isGroup.get() ? currentTarget.get() : "")
                            .setConteudo(Conteudo.newBuilder()
                                    .setTipo("text/plain")
                                    .setCorpo(ByteString.copyFromUtf8(input))
                                    .build())
                            .build();

                    byte[] mensagemBytes = mensagem.toByteArray();

                    if (isGroup.get()) {
                        channel.basicPublish(currentTarget.get(), "", null, mensagemBytes);
                    } else {
                        channel.basicPublish("", currentTarget.get(), null, mensagemBytes);
                    }
                } catch (Exception e) {
                    System.out.println("Erro ao enviar mensagem. Tentando reconectar...");
                    try {
                        channel = createChannel();
                    } catch (Exception reconnectError) {
                        System.out.println("Erro ao reconectar: " + reconnectError.getMessage());
                    }
                }
            }

            try {
                channel.queueDelete(username);
                channel.close();
                connection.close();
                System.out.println("Chat encerrado com sucesso!");
            } catch (Exception e) {
                System.out.println("Erro ao limpar recursos: " + e.getMessage());
            }
        }
    }

    private static void handleCommand(String input, String username, AtomicReference<String> currentTarget) {
        String[] parts = input.substring(1).split("\\s+");
        String command = parts[0].toLowerCase();

        try {
            if (!channel.isOpen()) {
                channel = createChannel();
            }

            switch (command) {
                case "addgroup":
                    if (parts.length == 2) {
                        String groupName = parts[1];
                        if (!checkGroupExists(groupName)) {
                            channel.exchangeDeclare(groupName, EXCHANGE_TYPE_FANOUT);
                            channel.queueBind(username, groupName, "");
                            System.out.println("Grupo '" + groupName + "' criado com sucesso!");
                        } else {
                            System.out.println("Erro: O grupo '" + groupName + "' já existe!");
                        }
                    }
                    break;

                case "adduser":
                    if (parts.length == 3) {
                        String userToAdd = parts[1];
                        String groupName = parts[2];
                        if (checkGroupExists(groupName) && checkUserExists(userToAdd)) {
                            channel.queueBind(userToAdd, groupName, "");
                            System.out.println("Usuário '" + userToAdd + "' adicionado ao grupo '" + groupName + "'");
                        } else {
                            System.out.println("Erro: Verifique se o grupo e o usuário existem!");
                        }
                    }
                    break;

                case "delfromgroup":
                    if (parts.length == 3) {
                        String userToRemove = parts[1];
                        String groupName = parts[2];
                        if (checkGroupExists(groupName) && checkUserExists(userToRemove)) {
                            channel.queueUnbind(userToRemove, groupName, "");
                            System.out.println("Usuário '" + userToRemove + "' removido do grupo '" + groupName + "'");
                        } else {
                            System.out.println("Erro: Verifique se o grupo e o usuário existem!");
                        }
                    }
                    break;

                case "removegroup":
                    if (parts.length == 2) {
                        String groupName = parts[1];
                        if (checkGroupExists(groupName)) {
                            channel.exchangeDelete(groupName);
                            System.out.println("Grupo '" + groupName + "' removido com sucesso!");
                        } else {
                            System.out.println("Erro: Grupo não encontrado!");
                        }
                    }
                    break;

                default:
                    System.out.println("Comando desconhecido. Comandos disponíveis: !addGroup, !addUser, !delFromGroup, !removeGroup, !exit");
            }
        } catch (Exception e) {
            System.out.println("Erro ao executar o comando: " + e.getMessage());
            try {
                channel = createChannel();
            } catch (Exception reconnectError) {
                System.out.println("Erro ao reconectar: " + reconnectError.getMessage());
            }
        }
    }
}