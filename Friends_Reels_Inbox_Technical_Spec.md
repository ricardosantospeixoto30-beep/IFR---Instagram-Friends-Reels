# Friends Reels Inbox — Technical Product Spec & Feasibility Test

## 1. Objetivo

Criar uma aplicação Android que funcione como uma interface alternativa para a caixa de entrada de Instagram Reels enviados por amigos.

Problema:
- Tenho centenas de Reels enviados em conversas de Instagram.
- Para os ver/reagir/responder, tenho de abrir a conversa, subir no histórico e procurar o Reel.
- Quero uma experiência semelhante ao feed de Reels: abrir uma vez, fazer swipe para o próximo e responder/reagir sem perder o contexto.
- Reels antigos também têm de ser descobertos/importados; não quero ter de reenviar manualmente cada Reel para outra app.

## 2. Experiência desejada

Ecrã principal:
- "Reels dos amigos"
- Feed vertical/full-screen.
- Um Reel por ecrã.
- Swipe para cima = próximo Reel.
- Swipe para baixo = Reel anterior.
- Filtros: Todos / Por ver / Não respondidos / Por pessoa / Por conversa.
- Contador opcional de Reels pendentes.

Para cada Reel:
- Vídeo/Reel original, idealmente reproduzido através do Instagram quando necessário.
- Nome/avatar da pessoa que enviou.
- Data aproximada do envio.
- A mensagem/texto que acompanhava o Reel, quando disponível.
- Estado: visto / não visto / respondido.
- Reações rápidas: ❤️ 😂 😭 💀 🔥 etc.
- Campo para escrever resposta.
- A ação de reagir/responder deve, se tecnicamente possível, ser enviada para a DM original do Instagram, e não ficar apenas dentro da app.
- Botão "Abrir no Instagram" como fallback.
- Depois de responder/reagir, manter o utilizador no feed e avançar facilmente para o próximo Reel.

## 3. Requisito crítico: respostas e reações

A prioridade é que:
1. O utilizador veja um Reel recebido.
2. Toque numa reação ou escreva uma resposta.
3. A reação/resposta seja enviada para a conversa original do Instagram.
4. O destinatário veja a reação/resposta normalmente no Instagram.

Não assumir que uma API oficial permite isto. Antes de implementar, investigar as APIs oficiais atuais e as limitações reais.

Se a API oficial não permitir:
- investigar automação Android apenas como protótipo;
- avaliar se AccessibilityService/automação é permitido para esta finalidade;
- avaliar alternativas como deep links, intents, notification actions ou integração limitada;
- nunca assumir que uma técnica de scraping funciona só porque funciona hoje.

## 4. Descoberta dos Reels antigos

Este é um requisito essencial.

A primeira execução deve, se tecnicamente possível:
- descobrir conversas existentes;
- permitir selecionar quais conversas analisar;
- procurar mensagens que contenham Reels/links de Instagram;
- importar os Reels encontrados;
- guardar uma referência ao Reel, conversa, remetente e mensagem original;
- evitar duplicados.

Não obrigar o utilizador a partilhar manualmente centenas de Reels um a um.

## 5. Seleção de conversas

A app deve suportar:

### Todas as conversas
- "Importar todas"

### Conversas específicas
- selecionar pessoas individualmente;
- selecionar grupos;
- pesquisar pelo nome da conversa;
- selecionar/desselecionar várias conversas.

### Configuração
Exemplo:
- Importar: Todas / Selecionadas
- Incluir grupos: Sim/Não
- Pessoas excluídas: lista
- Sincronização: manual / periódica, se tecnicamente possível
- Importar histórico antigo: Sim/Não
- Limite de histórico: sem limite / últimos X dias

A app deve deixar claro ao utilizador exatamente que dados está a tentar aceder.

## 6. Organização

Cada Reel deve ter metadados, quando disponíveis:
- reel_url
- sender_id/name
- conversation_id/name
- original_message_id (se disponível)
- sent_at
- imported_at
- viewed_at
- replied_at
- reaction_sent
- reply_text
- reply_status
- import_status

Possíveis estados:
- UNSEEN
- SEEN
- REPLIED
- REACTION_SENT
- FAILED
- UNAVAILABLE

## 7. UX proposta

Bottom navigation:
- Reels
- Conversas
- Definições

### Reels
Feed vertical.

### Conversas
Lista:
- João — 37 Reels
- Maria — 12 Reels
- Grupo X — 64 Reels

Ao abrir uma conversa:
- feed apenas dos Reels daquela conversa.

### Reels
Filtros:
- Todos
- Por ver
- Sem resposta
- Recentes
- Mais antigos

## 8. Conceito ideal de integração com Instagram

Objetivo máximo:
- Se for possível, existir uma integração que pareça uma nova tab ao lado das DMs no Instagram.
- Exemplo conceptual:
  Instagram: [Home] [Reels] [DM] [Friends Reels]

Mas isto NÃO é requisito técnico obrigatório.

Se não for possível modificar/adicionar uma tab dentro da app oficial do Instagram:
- criar uma aplicação Android separada;
- abrir a app normalmente;
- manter a experiência de feed dentro da nossa app;
- usar o Instagram apenas para autenticação/visualização/ações suportadas.

Nunca construir o projeto assumindo que podemos modificar a app oficial do Instagram sem suporte oficial.

## 9. Segurança e privacidade

- Não pedir password do Instagram diretamente à nossa app.
- Preferir APIs oficiais/OAuth quando existirem.
- Se forem usados mecanismos Android de automação, explicar claramente ao utilizador o que fazem e pedir consentimento.
- Guardar o mínimo de dados possível.
- Idealmente guardar referências/IDs/URLs em vez de descarregar permanentemente vídeos.
- Não enviar dados das DMs para um servidor sem necessidade.
- Considerar armazenamento local por defeito.
- Ter opção para apagar toda a biblioteca importada.

## 10. Deployment / publicação (opcional)

A publicação numa loja de aplicações pode ser considerada mais tarde, mas **não deve condicionar a arquitetura nem o MVP inicial**.

O objetivo principal nesta fase é descobrir a abordagem tecnicamente mais robusta para:
- importar Reels antigos das DMs;
- identificar remetente/conversa;
- mostrar os Reels num feed;
- enviar respostas e reações para a conversa original.

Se a melhor solução encontrada tiver limitações de distribuição, isso deve ser documentado separadamente em vez de comprometer a investigação técnica.

## 11. Plano de testes de viabilidade

### Teste A — Descobrir a melhor integração possível
Investigar **todas as abordagens plausíveis**, sem assumir previamente que a solução será API oficial ou scraping.

Começar por:
- Instagram Graph API
- Instagram Messaging API
- outras APIs/SDKs oficiais da Meta/Instagram relevantes
- permissões disponíveis
- acesso a DMs pessoais
- leitura de mensagens contendo Reels
- identificação de remetente/conversa
- envio de respostas
- envio de reações

Depois investigar outras possibilidades tecnicamente legítimas, por exemplo:
- Android intents/deep links;
- notificações e notification actions;
- mecanismos de partilha;
- AccessibilityService/automação de UI;
- integração local entre apps;
- abordagens híbridas;
- qualquer outra técnica que a AI considere mais robusta.

Para cada abordagem, explicar:
- dados que consegue obter;
- ações que consegue executar;
- necessidade de interação manual;
- estabilidade;
- limitações;
- riscos;
- dependências;
- se permite cumprir o requisito de resposta/reação na DM original.

Resultado:
- criar uma matriz POSSÍVEL / LIMITADA / NÃO DISPONÍVEL para cada capacidade;
- escolher a abordagem ou combinação de abordagens mais promissora;
- justificar tecnicamente a escolha.

### Teste B — Android intents/deep links
Verificar se é possível:
- abrir Reel específico no Instagram;
- abrir conversa específica;
- voltar para a app;
- iniciar uma resposta no contexto correto.

### Teste C — AccessibilityService
Criar um protótipo local, sem servidor, para verificar se é possível:
- abrir Instagram;
- navegar até DMs;
- selecionar uma conversa;
- percorrer mensagens;
- identificar elementos de Reel;
- extrair URLs/texto;
- abrir um Reel;
- introduzir uma resposta;
- enviar uma reação/resposta.

É apenas um teste técnico; a compatibilidade de distribuição deve ser avaliada separadamente, se necessário.

### Teste D — histórico
Com uma conta de teste contendo muitas conversas e Reels:
- importar 10;
- importar 100;
- importar 500+;
- medir duplicados;
- medir falhas;
- verificar se o remetente/conversa é corretamente associado.

### Teste E — UX
Medir quantos passos são necessários para:
- ver próximo Reel;
- reagir;
- responder;
- voltar ao Instagram;
- continuar para o próximo.

Meta:
- ver próximo Reel: 1 gesto;
- reação: 1 toque;
- resposta: 1 toque + escrever;
- continuar: 1 swipe.

## 12. Critério de sucesso do MVP

O MVP é considerado bem sucedido se, com uma conta de teste:
1. Conseguir descobrir/importar Reels antigos recebidos em DMs sem reenviar cada Reel manualmente.
2. Conseguir selecionar todas ou apenas algumas conversas/grupos.
3. Mostrar os Reels num feed vertical.
4. Conseguir marcar vistos.
5. Conseguir identificar corretamente remetente e conversa.
6. Conseguir reagir ou responder através do Instagram, se tecnicamente permitido.
7. Ter "Abrir no Instagram" como fallback.
8. Não exigir guardar a password do Instagram na app.

## 13. Ordem recomendada de desenvolvimento

FASE 0 — Pesquisa técnica
Não programar a app completa ainda.

FASE 1 — Proof of Concept
Testar acesso/importação de uma única conversa.

FASE 2 — Feed
Criar o feed vertical com dados fictícios/importados.

FASE 3 — Várias conversas
Adicionar seleção de conversas/grupos.

FASE 4 — Ações
Testar resposta e reação no Instagram.

FASE 5 — Histórico grande
Testar centenas/milhares de Reels.

FASE 6 — Segurança/privacidade
Rever armazenamento, permissões e consentimento.

FASE 7 — Validação final
Só depois avaliar os requisitos finais de distribuição e implementação.

## 14. Instrução para outra AI
"Não comeces por construir a UI. Primeiro faz uma análise de viabilidade técnica em 2026.

O requisito mais importante é descobrir se existe alguma forma legítima e tecnicamente robusta de ler/importar os Reels recebidos nas DMs de uma conta Instagram e, idealmente, enviar reações/respostas para a DM original.

Investiga primeiro APIs oficiais. Se forem insuficientes, avalia separadamente Android intents, notification APIs, AccessibilityService e outras técnicas de automação. Para cada técnica, indica:
- o que consegue fazer;
- o que não consegue;
- riscos de segurança;
- riscos de quebra;
- compatibilidade com a distribuição pretendida;
- dependências;
- se exige interação manual.

Não proponhas como solução final uma técnica que apenas funcione teoricamente.

Depois cria um pequeno Proof of Concept para testar uma única conversa antes de implementar a aplicação completa.
".
