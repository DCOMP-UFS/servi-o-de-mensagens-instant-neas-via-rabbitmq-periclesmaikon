//mvn compile assembly:single
//java -jar target/ChatRabbitMQ-1.0-SNAPSHOT-jar-with-dependencies.jar

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
    private static final String TIPO_EXCHANGE_FANOUT = "fanout";
    private static volatile boolean executando = true;
    private static Connection conexao;
    private static Channel canal;
    private static final String DIR_DOWNLOAD = System.getProperty("user.home") + File.separator + "chat" + File.separator + "downloads";
    private static ExecutorService executorTransferenciaArquivos = Executors.newCachedThreadPool();
    
    private static Channel criarCanal() throws Exception {
        if (conexao == null || !conexao.isOpen()) {
            ConnectionFactory fabrica = new ConnectionFactory();
            fabrica.setHost("3.86.30.107");
            fabrica.setUsername("admin");
            fabrica.setPassword("password");
            fabrica.setVirtualHost("/");
            conexao = fabrica.newConnection();
        }
        return conexao.createChannel();
    }

    private static boolean verificarUsuarioExiste(String nomeUsuario) {
        try {
            Channel canalTemporario = criarCanal();
            canalTemporario.queueDeclarePassive(nomeUsuario);
            canalTemporario.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean verificarGrupoExiste(String nomeGrupo) {
        try {
            Channel canalTemporario = criarCanal();
            canalTemporario.exchangeDeclarePassive(nomeGrupo);
            canalTemporario.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    private static void garantirDiretorioDownloadExiste() {
        try {
            Files.createDirectories(Paths.get(DIR_DOWNLOAD));
        } catch (IOException e) {
            System.err.println("Erro ao criar diretório de downloads: " + e.getMessage());
        }
    }

    public static void main(String[] argv) throws Exception {
        // Garantir que o diretório de download existe
        garantirDiretorioDownloadExiste();
        
        try (Scanner scanner = new Scanner(System.in)) {
            System.out.print("User: ");
            String nomeUsuario = scanner.nextLine();
            
            canal = criarCanal();
            String filaUsuario = nomeUsuario;
            String filaArquivos = nomeUsuario + "_files";
            
            // Declarar fila do usuário para mensagens
            canal.queueDeclare(filaUsuario, false, false, false, null);
            
            // Declarar fila do usuário para arquivos
            canal.queueDeclare(filaArquivos, false, false, false, null);

            System.out.println("Bem-vindo, " + nomeUsuario + "! Você pode começar a enviar mensagens.\n");
            System.out.println("Comandos disponíveis:");
            System.out.println("- !upload <caminho_arquivo> : Enviar arquivo para o usuário ou grupo atual");
            System.out.println("- !addGroup <nomeGrupo> : Criar novo grupo");
            System.out.println("- !addUser <nomeUsuario> <nomeGrupo> : Adicionar usuário ao grupo");
            System.out.println("- !delFromGroup <nomeUsuario> <nomeGrupo> : Remover usuário do grupo");
            System.out.println("- !removeGroup <nomeGrupo> : Excluir grupo");
            System.out.println("- !exit : Sair do chat");
            System.out.println("- #<nomeGrupo> : Entrar em modo de grupo");
            System.out.println("- @<nomeUsuario> : Conversar com usuário\n");
            System.out.println("Arquivos recebidos serão salvos em: " + DIR_DOWNLOAD);

            AtomicReference<String> destinoAtual = new AtomicReference<>("");
            AtomicReference<Boolean> eGrupo = new AtomicReference<>(false);

            // Thread para consumir mensagens de texto
            Thread threadConsumidorTexto = new Thread(() -> {
                try {
                    Consumer consumidor = new DefaultConsumer(canal) {
                        @Override
                        public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) throws IOException {
                            if (!executando) return;
                            
                            Mensagem mensagemRecebida = Mensagem.parseFrom(body);
                            String remetente = mensagemRecebida.getEmissor();
                            String data = mensagemRecebida.getData();
                            String hora = mensagemRecebida.getHora();
                            String grupo = mensagemRecebida.getGrupo();
                            String conteudo = mensagemRecebida.getConteudo().getCorpo().toStringUtf8();

                            String mensagemExibicao;
                            if (!grupo.isEmpty()) {
                                mensagemExibicao = String.format("(%s às %s) %s#%s diz: %s", data, hora, remetente, grupo, conteudo);
                            } else {
                                mensagemExibicao = String.format("(%s às %s) %s diz: %s", data, hora, remetente, conteudo);
                            }

                            System.out.println(mensagemExibicao);

                            synchronized (System.out) {
                                if (eGrupo.get()) {
                                    System.out.print("#" + destinoAtual.get() + ">> ");
                                } else {
                                    System.out.print(destinoAtual.get().isEmpty() ? ">> " : "@" + destinoAtual.get() + ">> ");
                                }
                            }
                        }
                    };
                    canal.basicConsume(filaUsuario, true, consumidor);
                } catch (IOException e) {
                    if (executando) {
                        e.printStackTrace();
                    }
                }
            });
            threadConsumidorTexto.start();
            
            // Thread para consumir arquivos
            Thread threadConsumidorArquivos = new Thread(() -> {
                try {
                    Consumer consumidorArquivos = new DefaultConsumer(canal) {
                        @Override
                        public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) throws IOException {
                            if (!executando) return;
                            
                            // Garantir que o diretório de download existe antes de salvar o arquivo
                            garantirDiretorioDownloadExiste();
                            
                            Mensagem mensagemRecebida = Mensagem.parseFrom(body);
                            String remetente = mensagemRecebida.getEmissor();
                            String data = mensagemRecebida.getData();
                            String hora = mensagemRecebida.getHora();
                            
                            Conteudo conteudo = mensagemRecebida.getConteudo();
                            String tipoMime = conteudo.getTipo();
                            ByteString arquivoBytes = conteudo.getCorpo();
                            
                            // Obter nome do arquivo dos headers
                            String nomeArquivo = properties.getHeaders().get("fileName").toString();
                            
                            // Salvar arquivo no diretório de downloads
                            Path caminhoArquivo = Paths.get(DIR_DOWNLOAD, nomeArquivo);
                            Files.write(caminhoArquivo, arquivoBytes.toByteArray());
                            
                            String mensagemExibicao = String.format("(%s às %s) Arquivo \"%s\" recebido de @%s !", 
                                data, hora, nomeArquivo, remetente);
                            
                            synchronized (System.out) {
                                System.out.println(mensagemExibicao);
                                if (eGrupo.get()) {
                                    System.out.print("#" + destinoAtual.get() + ">> ");
                                } else {
                                    System.out.print(destinoAtual.get().isEmpty() ? ">> " : "@" + destinoAtual.get() + ">> ");
                                }
                            }
                        }
                    };
                    canal.basicConsume(filaArquivos, true, consumidorArquivos);
                } catch (IOException e) {
                    if (executando) {
                        e.printStackTrace();
                    }
                }
            });
            threadConsumidorArquivos.start();

            while (executando) {
                if (eGrupo.get()) {
                    System.out.print("#" + destinoAtual.get() + ">> ");
                } else if (!destinoAtual.get().isEmpty()) {
                    System.out.print("@" + destinoAtual.get() + ">> ");
                } else {
                    System.out.print(">> ");
                }

                String entrada = scanner.nextLine();

                if (entrada.startsWith("!")) {
                    if (entrada.equalsIgnoreCase("!exit")) {
                        System.out.println("Encerrando o chat...");
                        executando = false;
                        break;
                    } else if (entrada.startsWith("!upload ")) {
                        if (destinoAtual.get().isEmpty()) {
                            System.out.println("Erro: Você precisa selecionar um destinatário antes de enviar arquivos.");
                            continue;
                        }
                        
                        String caminhoArquivo = entrada.substring("!upload ".length()).trim();
                        manipularUploadArquivo(caminhoArquivo, nomeUsuario, destinoAtual.get(), eGrupo.get());
                        continue;
                    }
                    manipularComando(entrada, nomeUsuario, destinoAtual);
                    continue;
                }

                if (entrada.startsWith("#")) {
                    String nomeGrupo = entrada.substring(1).trim();
                    if (verificarGrupoExiste(nomeGrupo)) {
                        destinoAtual.set(nomeGrupo);
                        eGrupo.set(true);
                        System.out.println("Entrando no grupo: " + nomeGrupo);
                    } else {
                        System.out.println("Erro: O grupo '" + nomeGrupo + "' não existe!");
                    }
                    continue;
                }

                if (entrada.startsWith("@")) {
                    String usuarioDestino = entrada.substring(1).trim();
                    if (verificarUsuarioExiste(usuarioDestino)) {
                        destinoAtual.set(usuarioDestino);
                        eGrupo.set(false);
                        System.out.println("Iniciando conversa com: " + usuarioDestino);
                    } else {
                        System.out.println("Erro: O usuário '" + usuarioDestino + "' não existe!");
                    }
                    continue;
                }

                if (destinoAtual.get().isEmpty()) {
                    System.out.println("Por favor, defina um destinatário usando @nome_do_usuario ou #nome_do_grupo");
                    continue;
                }

                try {
                    if (!canal.isOpen()) {
                        canal = criarCanal();
                    }

                    String dataAtual = new SimpleDateFormat("dd/MM/yyyy").format(new Date());
                    String horaAtual = new SimpleDateFormat("HH:mm").format(new Date());

                    Mensagem mensagem = Mensagem.newBuilder()
                            .setEmissor(nomeUsuario)
                            .setData(dataAtual)
                            .setHora(horaAtual)
                            .setGrupo(eGrupo.get() ? destinoAtual.get() : "")
                            .setConteudo(Conteudo.newBuilder()
                                    .setTipo("text/plain")
                                    .setCorpo(ByteString.copyFromUtf8(entrada))
                                    .build())
                            .build();

                    byte[] mensagemBytes = mensagem.toByteArray();

                    if (eGrupo.get()) {
                        canal.basicPublish(destinoAtual.get(), "", null, mensagemBytes);
                    } else {
                        canal.basicPublish("", destinoAtual.get(), null, mensagemBytes);
                    }
                } catch (Exception e) {
                    System.out.println("Erro ao enviar mensagem. Tentando reconectar...");
                    try {
                        canal = criarCanal();
                    } catch (Exception erroReconexao) {
                        System.out.println("Erro ao reconectar: " + erroReconexao.getMessage());
                    }
                }
            }

            executorTransferenciaArquivos.shutdown();
            try {
                executorTransferenciaArquivos.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                System.out.println("Aguardando finalização das transferências de arquivos...");
            }
            
            try {
                canal.queueDelete(nomeUsuario);
                canal.queueDelete(nomeUsuario + "_files");
                canal.close();
                conexao.close();
                System.out.println("Chat encerrado com sucesso!");
            } catch (Exception e) {
                System.out.println("Erro ao limpar recursos: " + e.getMessage());
            }
        }
    }
    
    private static void manipularUploadArquivo(String caminhoArquivo, String nomeUsuario, String destino, boolean eGrupo) {
        if (caminhoArquivo.isEmpty()) {
            System.out.println("Erro: Caminho do arquivo não especificado!");
            return;
        }
        
        File arquivo = new File(caminhoArquivo);
        if (!arquivo.exists() || !arquivo.isFile()) {
            System.out.println("Erro: Arquivo não encontrado: " + caminhoArquivo);
            return;
        }
        
        String exibicaoDestino = eGrupo ? "#" + destino : "@" + destino;
        System.out.println("Enviando \"" + caminhoArquivo + "\" para " + exibicaoDestino + ".");
        
        executorTransferenciaArquivos.submit(() -> {
            try {
                Channel canalArquivo = criarCanal();
                
                Path origem = Paths.get(caminhoArquivo);
                String nomeArquivo = origem.getFileName().toString();
                String tipoMime = Files.probeContentType(origem);
                if (tipoMime == null) {
                    tipoMime = "application/octet-stream";
                }
                
                byte[] dadosArquivo = Files.readAllBytes(origem);
                
                String dataAtual = new SimpleDateFormat("dd/MM/yyyy").format(new Date());
                String horaAtual = new SimpleDateFormat("HH:mm").format(new Date());
                
                Mensagem mensagem = Mensagem.newBuilder()
                        .setEmissor(nomeUsuario)
                        .setData(dataAtual)
                        .setHora(horaAtual)
                        .setGrupo(eGrupo ? destino : "")
                        .setConteudo(Conteudo.newBuilder()
                                .setTipo(tipoMime)
                                .setCorpo(ByteString.copyFrom(dadosArquivo))
                                .build())
                        .build();
                
                byte[] mensagemBytes = mensagem.toByteArray();
                
                // Adicionar nome do arquivo nos headers
                Map<String, Object> cabecalhos = new HashMap<>();
                cabecalhos.put("fileName", nomeArquivo);
                AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                        .headers(cabecalhos)
                        .build();
                
                if (eGrupo) {
                    // Para grupos, descobrir todas as filas de arquivo conectadas
                    String consultaVinculosGrupo = destino;
                    Set<String> filasVinculadas = new HashSet<>();
                    
                    try {
                        Channel canalTemporario = criarCanal();
                        // Esta implementação é simplificada para a demonstração
                        // Na prática, você precisaria de uma solução mais robusta
                        for (String membroPotencial : obterMembrosPotenciaisGrupo()) {
                            try {
                                canalTemporario.queueDeclarePassive(membroPotencial);
                                if (estaFilaVinculadaAoExchange(membroPotencial, consultaVinculosGrupo)) {
                                    filasVinculadas.add(membroPotencial + "_files");
                                }
                            } catch (Exception e) {
                                // Queue doesn't exist or isn't bound to this exchange
                            }
                        }
                        canalTemporario.close();
                    } catch (Exception e) {
                        System.out.println("Erro ao determinar membros do grupo: " + e.getMessage());
                    }
                    
                    // Publicar para cada fila de arquivo dos membros do grupo
                    for (String filaArquivoMembro : filasVinculadas) {
                        if (!filaArquivoMembro.equals(nomeUsuario + "_files")) { // Não enviar para si mesmo
                            canalArquivo.basicPublish("", filaArquivoMembro, props, mensagemBytes);
                        }
                    }
                } else {
                    // Para usuário individual, enviar diretamente para sua fila de arquivos
                    canalArquivo.basicPublish("", destino + "_files", props, mensagemBytes);
                }
                
                canalArquivo.close();
                
                System.out.println("Arquivo \"" + caminhoArquivo + "\" foi enviado para " + exibicaoDestino + " !");
            } catch (Exception e) {
                System.out.println("Erro ao enviar arquivo: " + e.getMessage());
            }
        });
    }
    
    // Método auxiliar para verificar se uma fila está ligada a um exchange
    private static boolean estaFilaVinculadaAoExchange(String nomeFila, String nomeExchange) {
        try {
            // Esta é uma implementação simplificada
            // Na prática, seria necessário usar a API de gerenciamento do RabbitMQ 
            // ou manter um registro local das associações
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    // Método auxiliar simplificado para demonstração
    private static List<String> obterMembrosPotenciaisGrupo() {
        List<String> membrosPotenciais = new ArrayList<>();
        try {
            // Na prática, você precisaria:
            // 1. Manter um registro de usuários
            // 2. Ou usar a API do RabbitMQ Management para consultar filas
            
            // Retornamos uma lista vazia para simplificar o exemplo
        } catch (Exception e) {
            System.out.println("Erro ao obter lista de usuários: " + e.getMessage());
        }
        return membrosPotenciais;
    }

    private static void manipularComando(String entrada, String nomeUsuario, AtomicReference<String> destinoAtual) {
        String[] partes = entrada.substring(1).split("\\s+");
        String comando = partes[0].toLowerCase();

        try {
            if (!canal.isOpen()) {
                canal = criarCanal();
            }

            switch (comando) {
                case "addgroup":
                    if (partes.length == 2) {
                        String nomeGrupo = partes[1];
                        if (!verificarGrupoExiste(nomeGrupo)) {
                            canal.exchangeDeclare(nomeGrupo, TIPO_EXCHANGE_FANOUT);
                            canal.queueBind(nomeUsuario, nomeGrupo, "");
                            System.out.println("Grupo '" + nomeGrupo + "' criado com sucesso!");
                        } else {
                            System.out.println("Erro: O grupo '" + nomeGrupo + "' já existe!");
                        }
                    }
                    break;

                case "adduser":
                    if (partes.length == 3) {
                        String usuarioAdicionar = partes[1];
                        String nomeGrupo = partes[2];
                        if (verificarGrupoExiste(nomeGrupo) && verificarUsuarioExiste(usuarioAdicionar)) {
                            canal.queueBind(usuarioAdicionar, nomeGrupo, "");
                            System.out.println("Usuário '" + usuarioAdicionar + "' adicionado ao grupo '" + nomeGrupo + "'");
                        } else {
                            System.out.println("Erro: Verifique se o grupo e o usuário existem!");
                        }
                    }
                    break;

                case "delfromgroup":
                    if (partes.length == 3) {
                        String usuarioRemover = partes[1];
                        String nomeGrupo = partes[2];
                        if (verificarGrupoExiste(nomeGrupo) && verificarUsuarioExiste(usuarioRemover)) {
                            canal.queueUnbind(usuarioRemover, nomeGrupo, "");
                            System.out.println("Usuário '" + usuarioRemover + "' removido do grupo '" + nomeGrupo + "'");
                        } else {
                            System.out.println("Erro: Verifique se o grupo e o usuário existem!");
                        }
                    }
                    break;

                case "removegroup":
                    if (partes.length == 2) {
                        String nomeGrupo = partes[1];
                        if (verificarGrupoExiste(nomeGrupo)) {
                            canal.exchangeDelete(nomeGrupo);
                            System.out.println("Grupo '" + nomeGrupo + "' removido com sucesso!");
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
                canal = criarCanal();
            } catch (Exception erroReconexao) {
                System.out.println("Erro ao reconectar: " + erroReconexao.getMessage());
            }
        }
    }
}