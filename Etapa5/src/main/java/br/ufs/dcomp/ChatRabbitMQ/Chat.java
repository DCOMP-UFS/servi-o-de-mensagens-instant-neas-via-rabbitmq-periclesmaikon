//OS arquivos recebidos por um user só aparecem em downloads se você abrir um terminal pra ele


package br.ufs.dcomp.ChatRabbitMQ;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
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
    private static final String RABBITMQ_API_URL = "http://18.204.207.156:15672/api/";
    private static final String RABBITMQ_API_USERNAME = "admin";
    private static final String RABBITMQ_API_PASSWORD = "password";
    
    private static Channel createChannel() throws Exception {
        if (connection == null || !connection.isOpen()) {
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost("18.204.207.156");
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
            System.out.println("- !listUsers <nomeGrupo> : Listar todos os usuários de um grupo");
            System.out.println("- !listGroups : Listar todos os grupos dos quais você faz parte");
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
                            
                            try {
                                Mensagem mensagemRecebida = Mensagem.parseFrom(body);
                                String sender = mensagemRecebida.getEmissor();
                                String data = mensagemRecebida.getData();
                                String hora = mensagemRecebida.getHora();
                                String grupo = mensagemRecebida.getGrupo();
                                String conteudo = mensagemRecebida.getConteudo().getCorpo().toStringUtf8();
            
                                
                                String displayMessage;
                                if (!grupo.isEmpty()) {
                                    displayMessage = String.format("(%s às %s) %s#%s diz: %s \n", data, hora, sender, grupo, conteudo);
                                    System.out.println(displayMessage);
                                } else {
                                    // Para mensagens diretas, continue exibindo normalmente
                                    displayMessage = String.format("(%s às %s) %s diz: %s \n", data, hora, sender, conteudo);
                                    System.out.println(displayMessage);
                                }
            
                                synchronized (System.out) {
                                    if (isGroup.get()) {
                                        System.out.print("#" + currentTarget.get() + ">> ");
                                    } else {
                                        System.out.print(currentTarget.get().isEmpty() ? ">> " : "@" + currentTarget.get() + ">> ");
                                    }
                                }
                            } catch (Exception e) {
                                System.out.println("Erro ao processar mensagem recebida: " + e.getMessage());
                            }
                        }
                    };
                    
                    // Importante: verificar se está consumindo da fila correta
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
                            
                            String displayMessage = String.format("(%s às %s) Arquivo \"%s\" recebido de @%s !\n", 
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
                        System.out.println("Entrando no grupo: " + groupName + "\n");
                        
                        // Verificar se o usuário atual está vinculado ao grupo
                        try {
                            // Verificar se a fila do usuário está vinculada à exchange do grupo
                            // Se não estiver, vincular automaticamente
                            if (!isQueueBoundToExchange(username, groupName)) {
                                channel.queueBind(username, groupName, "");
                                channel.queueBind(username + "_files", groupName, "");
                                System.out.println("Você foi automaticamente adicionado ao grupo: " + groupName + "\n");
                            }
                        } catch (Exception e) {
                            System.out.println("Erro ao verificar vinculação: " + e.getMessage());
                        }
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
                System.out.println("Aguardando finalização das transferências de arquivos...\n");
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
            System.out.println("Erro: Caminho do arquivo não especificado!\n");
            return;
        }
        
        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
            System.out.println("Erro: Arquivo não encontrado: " + filePath);
            return;
        }
        
        String targetDisplay = isGroup ? "#" + target : "@" + target;
        System.out.println("Enviando \"" + file.getName() + "\" para " + targetDisplay + "..." + "\n");
        
        fileTransferExecutor.submit(() -> {
            try {
                Channel fileChannel = createChannel();
                
                String fileName = file.getName();
                String mimeType = Files.probeContentType(file.toPath());
                if (mimeType == null) {
                    mimeType = "application/octet-stream";
                }
                
                byte[] fileData = Files.readAllBytes(file.toPath());
                
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
                
                Map<String, Object> headers = new HashMap<>();
                headers.put("fileName", fileName);
                AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                        .headers(headers)
                        .build();
                
                if (isGroup) {
                    // Para grupos, publicar na exchange do grupo
                    System.out.println("Publicando arquivo na exchange do grupo: " + target + "\n");
                    fileChannel.basicPublish(target, "", props, mensagemBytes);
                } else {
                    // Para usuário individual
                    System.out.println("Enviando arquivo diretamente para a fila: " + target + "_files\n");
                    fileChannel.basicPublish("", target + "_files", props, mensagemBytes);
                }
                
                System.out.println("Arquivo \"" + fileName + "\" enviado com sucesso para " + targetDisplay + "!\n");
            } catch (Exception e) {
                System.out.println("Erro ao enviar arquivo: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }
    
    // Método auxiliar para verificar se uma fila está ligada a um exchange
    private static boolean isQueueBoundToExchange(String queueName, String exchangeName) {
        try {
            Channel tempChannel = createChannel();
            try {
                // Testar a vinculação tentando removê-la e depois adicioná-la novamente
                tempChannel.queueUnbind(queueName, exchangeName, "");
                tempChannel.queueBind(queueName, exchangeName, "");
                tempChannel.close();
                return true;
            } catch (Exception e) {
                tempChannel.close();
                return false;
            }
        } catch (Exception e) {
            return false;
        }
    }
    
    // Método auxiliar simplificado para demonstração
    private static List<String> getPotentialGroupMembers() {
        List<String> potentialMembers = new ArrayList<>();
        try {
            // Na prática, você precisaria:
            // 1. Manter um registro de usuários
            // 2. Ou usar a API do RabbitMQ Management para consultar filas
            
            // Retornamos uma lista vazia para simplificar o exemplo
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
                            System.out.println("Criando grupo: " + groupName + "\n");
                            // Declarar exchange do tipo fanout
                            channel.exchangeDeclare(groupName, EXCHANGE_TYPE_FANOUT, false);
                            // Vincular a fila do usuário ao grupo
                            channel.queueBind(username, groupName, "");
                            // Vincular também a fila de arquivos
                            channel.queueBind(username + "_files", groupName, "");
                            System.out.println("Grupo '" + groupName + "' criado com sucesso!\n");
                        } else {
                            System.out.println("Erro: O grupo '" + groupName + "' já existe!\n");
                        }
                    } else {
                        System.out.println("Uso: !addgroup <nomeGrupo>");
                    }
                    break;

                case "adduser":
                    if (parts.length == 3) {
                        String userToAdd = parts[1];
                        String groupName = parts[2];
                        if (checkGroupExists(groupName) && checkUserExists(userToAdd)) {
                            System.out.println("Adicionando " + userToAdd + " ao grupo " + groupName + "\n");
                            // Vincular fila de mensagens
                            channel.queueBind(userToAdd, groupName, "");
                            // Vincular fila de arquivos
                            channel.queueBind(userToAdd + "_files", groupName, "");
                            System.out.println("Usuário '" + userToAdd + "' adicionado ao grupo '" + groupName + "'\n");
                        } else {
                            System.out.println("Erro: Verifique se o grupo e o usuário existem!");
                        }
                    } else {
                        System.out.println("Uso: !adduser <nomeUsuario> <nomeGrupo>");
                    }
                    break;

                case "delfromgroup":
                    if (parts.length == 3) {
                        String userToRemove = parts[1];
                        String groupName = parts[2];
                        if (checkGroupExists(groupName) && checkUserExists(userToRemove)) {
                            System.out.println("Removendo " + userToRemove + " do grupo " + groupName + "\n");
                            // Desvincular fila de mensagens
                            channel.queueUnbind(userToRemove, groupName, "");
                            // Desvincular fila de arquivos
                            channel.queueUnbind(userToRemove + "_files", groupName, "");
                            System.out.println("Usuário '" + userToRemove + "' removido do grupo '" + groupName + "'\n");
                        } else {
                            System.out.println("Erro: Verifique se o grupo e o usuário existem!");
                        }
                    } else {
                        System.out.println("Uso: !delfromgroup <nomeUsuario> <nomeGrupo>");
                    }
                    break;

                case "removegroup":
                    if (parts.length == 2) {
                        String groupName = parts[1];
                        if (checkGroupExists(groupName)) {
                            channel.exchangeDelete(groupName);
                            System.out.println("Grupo '" + groupName + "' removido com sucesso!\n");
                        } else {
                            System.out.println("Erro: Grupo não encontrado!\n");
                        }
                    }
                    break;
                    
                case "listusers":
                    if (parts.length == 2) {
                        String groupName = parts[1];
                        if (checkGroupExists(groupName)) {
                            listGroupUsers(groupName);
                        } else {
                            System.out.println("Erro: O grupo '" + groupName + "' não existe!\n");
                        }
                    } else {
                        System.out.println("Uso: !listUsers <nomeGrupo>");
                    }
                    break;

                case "listgroups":
                    listUserGroups(username);
                    break;

                default:
                    System.out.println("Comando desconhecido. Comandos disponíveis: !upload, !addGroup, !addUser, !delFromGroup, !removeGroup, !listUsers, !listGroups, !exit\n");
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
    
    private static String getBasicAuthHeader() {
        String auth = RABBITMQ_API_USERNAME + ":" + RABBITMQ_API_PASSWORD;
        return "Basic " + Base64.getEncoder().encodeToString(auth.getBytes());
    }
    
    private static String makeAPIRequest(String endpoint) throws Exception {
        URL url = new URL(RABBITMQ_API_URL + endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", getBasicAuthHeader());
        conn.setRequestProperty("Content-Type", "application/json");
        
        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            throw new RuntimeException("Falha na requisição: HTTP error code " + responseCode);
        }
        
        BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        String inputLine;
        StringBuilder response = new StringBuilder();
        
        while ((inputLine = in.readLine()) != null) {
            response.append(inputLine);
        }
        in.close();
        
        return response.toString();
    }
    
    private static void listGroupUsers(String groupName) {
        try {
            // Obter todas as vinculações (bindings) para a exchange do grupo
            String response = makeAPIRequest("exchanges/%2F/" + groupName + "/bindings/source");
            JSONArray bindings = new JSONArray(response);
            
            // Filtrar apenas usuários (filas que não terminam com "_files")
            List<String> users = new ArrayList<>();
            for (int i = 0; i < bindings.length(); i++) {
                JSONObject binding = bindings.getJSONObject(i);
                String queueName = binding.getString("destination");
                
                // Adicionar apenas se for uma fila de usuário (não termina com "_files")
                if (!queueName.endsWith("_files")) {
                    users.add(queueName);
                }
            }
            
            // Exibir a lista de usuários
            if (users.isEmpty()) {
                System.out.println("Nenhum usuário encontrado no grupo: " + groupName);
            } else {
                System.out.println(String.join(", ", users) + "\n");
            }
        } catch (Exception e) {
            System.out.println("Erro ao listar usuários do grupo: " + e.getMessage());
        }
    }

    private static void listUserGroups(String username) {
        try {
            // Obter todas as vinculações (bindings) para a fila do usuário
            String response = makeAPIRequest("queues/%2F/" + username + "/bindings");
            JSONArray bindings = new JSONArray(response);
            
            // Filtrar apenas exchanges de grupo (tipo fanout)
            List<String> groups = new ArrayList<>();
            for (int i = 0; i < bindings.length(); i++) {
                JSONObject binding = bindings.getJSONObject(i);
                String source = binding.getString("source");
                
                // Adicionar apenas se não for string vazia (o RabbitMQ usa "" para direct exchange default)
                if (!source.isEmpty()) {
                    // Verificar se a exchange é do tipo fanout (grupo)
                    try {
                        String exchangeInfo = makeAPIRequest("exchanges/%2F/" + source);
                        JSONObject exchange = new JSONObject(exchangeInfo);
                        if ("fanout".equals(exchange.getString("type"))) {
                            groups.add(source);
                        }
                    } catch (Exception e) {
                        // Ignora exchanges que não existem ou não são acessíveis
                    }
                }
            }
            
            // Exibir a lista de grupos
            if (groups.isEmpty()) {
                System.out.println("Você não participa de nenhum grupo.");
            } else {
                System.out.println(String.join(", ", groups) + "\n");
            }
        } catch (Exception e) {
            System.out.println("Erro ao listar grupos: " + e.getMessage());
        }
    }
    
    
}
