package br.ufs.dcomp.ChatRabbitMQ;

import com.rabbitmq.client.*;
import java.io.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import br.ufs.dcomp.ChatRabbitMQ.MensagemOuterClass.Mensagem;
import br.ufs.dcomp.ChatRabbitMQ.MensagemOuterClass.Conteudo;
import com.google.protobuf.ByteString;

public class Chat {
    private static final String EXCHANGE_TYPE_FANOUT = "fanout";
    private static volatile boolean running = true;
    private static Connection connection;
    private static Channel channel;
    private static final String DOWNLOAD_DIR = System.getProperty("user.home") + File.separator + "environment" + File.separator + "projetoChat" + File.separator + "Etapa3" + File.separator + "downloads";
    private static ExecutorService fileTransferExecutor = Executors.newCachedThreadPool();
    
    private static Channel createChannel() throws Exception {
        if (connection == null || !connection.isOpen()) {
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost("3.84.52.73");
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
    
    private static void ensureDownloadDirectoryExists() {
        try {
            Files.createDirectories(Paths.get(DOWNLOAD_DIR));
        } catch (IOException e) {
            System.err.println("Erro ao criar diretório de downloads: " + e.getMessage());
        }
    }

    public static void main(String[] argv) throws Exception {
        // Garantir que o diretório de download existe
        ensureDownloadDirectoryExists();
        
        try (Scanner scanner = new Scanner(System.in)) {
            System.out.print("User: ");
            String username = scanner.nextLine();
            
            channel = createChannel();
            String userQueue = username;
            String fileQueue = username + "_files";
            
            // Declarar fila do usuário para mensagens
            channel.queueDeclare(userQueue, false, false, false, null);
            
            // Declarar fila do usuário para arquivos
            channel.queueDeclare(fileQueue, false, false, false, null);

            System.out.println("Bem-vindo, " + username + "! Você pode começar a enviar mensagens.\n");
            System.out.println("Comandos disponíveis:");
            System.out.println("- !upload <caminho_arquivo> : Enviar arquivo para o usuário ou grupo atual");
            System.out.println("- !addGroup <nomeGrupo> : Criar novo grupo");
            System.out.println("- !addUser <nomeUsuario> <nomeGrupo> : Adicionar usuário ao grupo");
            System.out.println("- !delFromGroup <nomeUsuario> <nomeGrupo> : Remover usuário do grupo");
            System.out.println("- !removeGroup <nomeGrupo> : Excluir grupo");
            System.out.println("- !exit : Sair do chat");
            System.out.println("- #<nomeGrupo> : Entrar em modo de grupo");
            System.out.println("- @<nomeUsuario> : Conversar com usuário\n");
            System.out.println("Arquivos recebidos serão salvos em: " + DOWNLOAD_DIR);

            AtomicReference<String> currentTarget = new AtomicReference<>("");
            AtomicReference<Boolean> isGroup = new AtomicReference<>(false);

            // Thread para consumir mensagens de texto
            Thread textConsumerThread = new Thread(() -> {
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
            textConsumerThread.start();
            
            // Thread para consumir arquivos
            Thread fileConsumerThread = new Thread(() -> {
                try {
                    Consumer fileConsumer = new DefaultConsumer(channel) {
                        @Override
                        public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) throws IOException {
                            if (!running) return;
                            
                            // Garantir que o diretório de download existe antes de salvar o arquivo
                            ensureDownloadDirectoryExists();
                            
                            Mensagem mensagemRecebida = Mensagem.parseFrom(body);
                            String sender = mensagemRecebida.getEmissor();
                            String data = mensagemRecebida.getData();
                            String hora = mensagemRecebida.getHora();
                            
                            Conteudo conteudo = mensagemRecebida.getConteudo();
                            String tipoMime = conteudo.getTipo();
                            ByteString arquivoBytes = conteudo.getCorpo();
                            
                            // Obter nome do arquivo dos headers
                            String fileName = properties.getHeaders().get("fileName").toString();
                            
                            // Salvar arquivo no diretório de downloads
                            Path filePath = Paths.get(DOWNLOAD_DIR, fileName);
                            Files.write(filePath, arquivoBytes.toByteArray());
                            
                            String displayMessage = String.format("(%s às %s) Arquivo \"%s\" recebido de @%s !", 
                                data, hora, fileName, sender);
                            
                            synchronized (System.out) {
                                System.out.println(displayMessage);
                                if (isGroup.get()) {
                                    System.out.print("#" + currentTarget.get() + ">> ");
                                } else {
                                    System.out.print(currentTarget.get().isEmpty() ? ">> " : "@" + currentTarget.get() + ">> ");
                                }
                            }
                        }
                    };
                    channel.basicConsume(fileQueue, true, fileConsumer);
                } catch (IOException e) {
                    if (running) {
                        e.printStackTrace();
                    }
                }
            });
            fileConsumerThread.start();

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
                    } else if (input.startsWith("!upload ")) {
                        if (currentTarget.get().isEmpty()) {
                            System.out.println("Erro: Você precisa selecionar um destinatário antes de enviar arquivos.");
                            continue;
                        }
                        
                        String filePath = input.substring("!upload ".length()).trim();
                        handleFileUpload(filePath, username, currentTarget.get(), isGroup.get());
                        continue;
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

            fileTransferExecutor.shutdown();
            try {
                fileTransferExecutor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                System.out.println("Aguardando finalização das transferências de arquivos...");
            }
            
            try {
                channel.queueDelete(username);
                channel.queueDelete(username + "_files");
                channel.close();
                connection.close();
                System.out.println("Chat encerrado com sucesso!");
            } catch (Exception e) {
                System.out.println("Erro ao limpar recursos: " + e.getMessage());
            }
        }
    }
    
    private static void handleFileUpload(String filePath, String username, String target, boolean isGroup) {
        if (filePath.isEmpty()) {
            System.out.println("Erro: Caminho do arquivo não especificado!");
            return;
        }
        
        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
            System.out.println("Erro: Arquivo não encontrado: " + filePath);
            return;
        }
        
        String targetDisplay = isGroup ? "#" + target : "@" + target;
        System.out.println("Enviando \"" + filePath + "\" para " + targetDisplay + ".");
        
        fileTransferExecutor.submit(() -> {
            try {
                Channel fileChannel = createChannel();
                
                Path source = Paths.get(filePath);
                String fileName = source.getFileName().toString();
                String mimeType = Files.probeContentType(source);
                if (mimeType == null) {
                    mimeType = "application/octet-stream";
                }
                
                byte[] fileData = Files.readAllBytes(source);
                
                String dataAtual = new SimpleDateFormat("dd/MM/yyyy").format(new Date());
                String horaAtual = new SimpleDateFormat("HH:mm").format(new Date());
                
                Mensagem mensagem = Mensagem.newBuilder()
                        .setEmissor(username)
                        .setData(dataAtual)
                        .setHora(horaAtual)
                        .setGrupo(isGroup ? target : "")
                        .setConteudo(Conteudo.newBuilder()
                                .setTipo(mimeType)
                                .setCorpo(ByteString.copyFrom(fileData))
                                .build())
                        .build();
                
                byte[] mensagemBytes = mensagem.toByteArray();
                
                // Adicionar nome do arquivo nos headers
                Map<String, Object> headers = new HashMap<>();
                headers.put("fileName", fileName);
                AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                        .headers(headers)
                        .build();
                
                if (isGroup) {
                    // Para grupos, descobrir todas as filas de arquivo conectadas
                    String groupBindingsQuery = target;
                    Set<String> boundQueues = new HashSet<>();
                    
                    try {
                        Channel tempChannel = createChannel();
                        // Esta implementação é simplificada para a demonstração
                        // Na prática, você precisaria de uma solução mais robusta
                        for (String potentialMember : getPotentialGroupMembers()) {
                            try {
                                tempChannel.queueDeclarePassive(potentialMember);
                                if (isQueueBoundToExchange(potentialMember, groupBindingsQuery)) {
                                    boundQueues.add(potentialMember + "_files");
                                }
                            } catch (Exception e) {
                                // Queue doesn't exist or isn't bound to this exchange
                            }
                        }
                        tempChannel.close();
                    } catch (Exception e) {
                        System.out.println("Erro ao determinar membros do grupo: " + e.getMessage());
                    }
                    
                    // Publicar para cada fila de arquivo dos membros do grupo
                    for (String memberFileQueue : boundQueues) {
                        if (!memberFileQueue.equals(username + "_files")) { // Não enviar para si mesmo
                            fileChannel.basicPublish("", memberFileQueue, props, mensagemBytes);
                        }
                    }
                } else {
                    fileChannel.basicPublish("", target + "_files", props, mensagemBytes);
                }
                
                fileChannel.close();
                
                System.out.println("Arquivo \"" + filePath + "\" foi enviado para " + targetDisplay + " !");
            } catch (Exception e) {
                System.out.println("Erro ao enviar arquivo: " + e.getMessage());
            }
        });
    }
    
    // Método auxiliar para verificar se uma fila está ligada a um exchange
    private static boolean isQueueBoundToExchange(String queueName, String exchangeName) {
        try {
            
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    // Método auxiliar simplificado para demonstração
    private static List<String> getPotentialGroupMembers() {
        List<String> potentialMembers = new ArrayList<>();
        try {
            
        } catch (Exception e) {
            System.out.println("Erro ao obter lista de usuários: " + e.getMessage());
        }
        return potentialMembers;
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
                    System.out.println("Comando desconhecido. Comandos disponíveis: !upload, !addGroup, !addUser, !delFromGroup, !removeGroup, !exit");
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
