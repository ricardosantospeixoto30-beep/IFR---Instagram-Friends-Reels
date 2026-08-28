# PROJECT_PROGRESS — Friends Reels Inbox

> Ficheiro cumulativo de acompanhamento do projeto, conforme exigido pela spec §19.
> Atualizar sempre que houver decisões, investigação, testes ou mudanças relevantes.

---

## Estado atual

**Fase atual:** Fase 1 (PoC) — PoC-4 (identificar remetente) ✅ direção RECEIVED/SENT validada no OnePlus (sessão 13). Falta apenas identificar o **remetente humano em grupo** (sub-tarefa: precisa de dump da árvore de um bubble de grupo).
**Última atualização:** 2025-08-28 (sessão 13)
**Arquitetura escolhida:** Opção C — app externa Android + `AccessibilityService`.

### Como continuar na próxima sessão (quick start)

1. **Pull** do repo. Estado a partir da sessão 13 (PoC-4 validado; sub-task de grupo em aberto).
2. **Ler primeiro:**
    - Esta secção "Estado atual".
    - §6 "Próximos passos concretos" → **6.1** para a sub-tarefa de "nome do remetente em grupo" + plano do PoC-6.
    - Últimos logs de sessão em §7 (sessões 12 e 13).
3. **Ficheiros-chave a rever antes de mexer código:**
    - `app/src/main/java/com/example/friendsreels/service/InstagramReaderService.kt` — motor de a11y, gestos, dumps.
    - `app/src/main/java/com/example/friendsreels/instagram/IgSelectors.kt` — todos os IDs/labels do IG.
    - `docs/screen-dumps/dump-menu.txt` e `docs/screen-dumps/2025-08-28-initial-mapping.txt` — dumps de referência.
4. **Constraints do desenvolvimento** (importantes, não esquecer):
    - Todos os testes são feitos pelo utilizador num **OnePlus Nord 5 / Android 16**, num **PC separado com Android Studio**. Cada iteração exige `git commit && git push`. Minimizar rondas.
    - macOS deste ambiente **não tem Android SDK** — não é possível fazer build local, só validar sintaxe. Utilizador é sempre quem valida em device.
    - Java do sistema é 25 (parte Gradle 8.10.2). Se precisares mesmo de correr Gradle: `sdk use java 21.0.7-tem`.
5. **UX de teste actual:** notificação persistente "Friends Reels" no shade com botões ❤ / 😂 / Dump. Não usar os botões dentro da app (podem tirar o IG da conversa por causa da troca de foreground durante animações).
6. **MVP note:** o utilizador também envia Reels a amigos → o feed final tem de filtrar (via `sender_avatar`) para não mostrar os Reels enviados por ele.

---

## 1. Requisitos identificados (resumo da spec)

Ver `Friends_Reels_Inbox_Technical_Spec_v2.md` para o detalhe completo. Pontos-chave:

- Feed vertical/full-screen com um Reel por ecrã dos Reels **recebidos em DMs**.
- Descoberta de Reels em conversas existentes sem obrigar o utilizador a reencaminhar manualmente.
- **Novo (2025-08-28, sessão 8):** o utilizador também envia mensagens e Reels aos amigos. O MVP tem de distinguir mensagens **recebidas** (elegíveis para o feed) das **enviadas** por nós próprios (excluídas). Já temos o marcador certo — a presença do nó `sender_avatar` dentro de `message_content` indica mensagem recebida; a sua ausência indica mensagem enviada por nós. Ficará implementado no PoC-4 (identificação do remetente).
- Reações (❤️ 😂 na v1) e respostas ligadas à **mensagem original** na DM.
- Modos de seleção: *apenas selecionados* e *excluir selecionados*.
- Estados: `UNSEEN`, `SEEN`, `REACTION_SENT`, `REPLIED`, `FAILED`, `UNAVAILABLE` (não exclusivos).
- Fallbacks para abrir Reel/DM no Instagram nativo quando a ação não puder ser executada internamente.
- Segurança: sem password do IG, mínimo de dados, armazenamento local preferido.
- Prioridade estrita das soluções: **A → A.2/B → C**.

---

## 2. Fase 0 — Investigação de viabilidade

### 2.1 Opção A — Nova aba dentro do Instagram

**Resultado:** ❌ NÃO DISPONÍVEL em Instagram oficial.

**Justificação:**
- A app do Instagram (`com.instagram.android`) é distribuída pela Meta pela Play Store, assinada com a chave privada da Meta e com integrity checks.
- Adicionar uma nova entrada na barra de navegação inferior exige modificar o bytecode/recursos da APK. Isso obriga a:
  - Reverse-engineering da APK (APKTool/smali) **por cada versão** do Instagram, OU
  - Instalação de um APK modificado (Instander, Aero, GBInsta) com o código injetado, OU
  - Uso de LSPosed/Xposed com root para hook em runtime.
- Cada uma destas vias:
  - Exige perder o Instagram oficial (mesmo `package name`) ou substituí-lo por versão sem push notifications fiáveis.
  - Está sujeita a deteção pela Meta (com risco real de ban da conta).
  - Não é sustentável — cada update da app parte a modificação.

**Decisão do utilizador (2025-08-28):**
- Sem root, apenas Instagram oficial da Play Store.
- Aceita instalar APK modificado *em teoria*, mas depois de discutir os trade-offs (perda de IG oficial no telemóvel, risco de ban da conta pessoal com histórico real, manutenção contínua) concordou em **não seguir por aí**.

### 2.2 Opção A.2/B — Botão dentro de outra área do Instagram (DMs, etc.)

**Resultado:** ❌ NÃO DISPONÍVEL nas mesmas condições que A.

**Justificação:** Tem a mesma barreira técnica (modificar a APK do IG). Não muda nada em relação à Opção A do ponto de vista de viabilidade.

### 2.3 Opção C — App externa + AccessibilityService

**Resultado:** ✅ POSSÍVEL. Escolhida como arquitetura base.

**Justificação:**
- Funciona em Android 16 stock, Instagram oficial da Play Store, sem root.
- Não exige nenhuma alteração à APK do Instagram.
- Sem risco de ban da conta por modificações do cliente (o comportamento acedido é o mesmo que um utilizador humano executaria).
- A spec permite explicitamente esta opção quando A/A.2 forem inviáveis.
- Trade-offs aceites:
  - A experiência principal fica **fora** do IG (a spec §13 documenta como suportar este cenário: menu 3 pontinhos, "Abrir Reel no Instagram", "Abrir conversa no Instagram", etc.).
  - Descoberta de histórico em conta com >500 Reels vai ser **lenta** (a service precisa de "scrollar" cada conversa). Mitigável com sync em background/à noite via `WorkManager` + `foreground service` com finalidade `dataSync`.
  - Frágil a mudanças de UI do Instagram (nomes de recursos, `content-description`s, hierarquia). Mitigável centralizando seletores num único ficheiro e suportando dois idiomas (EN e PT) já desde o início.

**Como investigação futura (não urgente):** ficar em aberto a possibilidade de mais tarde experimentar um módulo LSPosed para prototipar uma versão da experiência integrada dentro do IG usando uma conta de teste separada. Apenas depois da app externa estar a funcionar.

### 2.4 Matriz Fase 0

| Capacidade | Opção A (IG mod) | Opção A.2/B (botão IG) | Opção C (App + a11y) | Melhor |
|---|---|---|---|---|
| Adicionar entry point dentro do IG | LIMITADO (com risco) | LIMITADO (com risco) | NÃO DISPONÍVEL | — |
| Descobrir conversas | POSSÍVEL (via internals) | POSSÍVEL | POSSÍVEL (via UI scroll) | A |
| Descobrir Reels em histórico | POSSÍVEL | POSSÍVEL | POSSÍVEL (lento) | A |
| Reagir na mensagem original | POSSÍVEL (via internals) | POSSÍVEL | POSSÍVEL (simular long-press + emoji) | A |
| Responder à mensagem original | POSSÍVEL | POSSÍVEL | POSSÍVEL (simular swipe reply + texto) | A |
| Segurança da conta principal | RISCO DE BAN | RISCO DE BAN | SEM RISCO | C |
| Manutenção a longo prazo | RUIM | RUIM | ACEITÁVEL | C |
| Compatibilidade Android 16 stock | LIMITADO | LIMITADO | POSSÍVEL | C |

**Escolha final:** Opção C. Melhor equilíbrio entre viabilidade real e segurança da conta do utilizador.

---

## 3. Fase 1 — Proof of Concept (em curso)

### Objetivo do PoC

Antes da app completa, provar experimentalmente que a Opção C consegue:

1. **PoC-1** — Instalar a app, ligar o `AccessibilityService`, e ver logcat quando o Instagram é aberto.
2. **PoC-2** — Detetar programaticamente o ecrã de Direct (Inbox), a lista de conversas, e extrair nomes.
3. **PoC-3** — Entrar numa conversa e detetar mensagens que contêm Reels; extrair `reel_url` (via long-press → "Copy link" ou deep-link).
4. **PoC-4** — Identificar o remetente do Reel (importante em grupos).
5. **PoC-5** — Enviar reação (❤️) para o Reel, com deteção da reação atual antes.
6. **PoC-6** — Enviar resposta ao Reel (comportamento equivalente ao Reply nativo).
7. **PoC-7** — Persistir Reels no Room DB sem duplicados.
8. **PoC-8** — Reproduzir os Reels descobertos num feed vertical Compose (via ExoPlayer + deep-link `instagram://reels_share`).

Cada PoC produz um resultado registado nesta secção quando testado.

### Estado do skeleton (commit inicial)

Já entregue no primeiro commit:

- Projeto Android limpo, Kotlin + Compose, com Gradle wrapper 8.10.2 e AGP 8.7.3.
- Package `com.example.friendsreels`.
- `minSdk 26` (Android 8), `targetSdk 35`. Escolhido `minSdk 26` para ter `AccessibilityService.dispatchGesture` estável e para não bloquear em APIs antigas.
- Manifest com:
  - `<queries>` para `com.instagram.android` (obrigatório em Android 11+ para conseguir usar `getLaunchIntentForPackage`).
  - Permissões `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`.
  - `InstagramReaderService` declarado como `AccessibilityService`.
- Room, DataStore, WorkManager, Media3/ExoPlayer, Navigation Compose já em dependências (não usados ainda, mas prontos).
- `MainActivity` com Compose e um botão para abrir as **definições de acessibilidade** do sistema.
- `InstagramReaderService` como placeholder que só regista o `onServiceConnected` no logcat.

**Como testar o skeleton no Android Studio (verificação de que o build passa):**

1. Abrir a pasta no Android Studio (File → Open…). O Android Studio vai criar `local.properties` com o SDK path.
2. Deixar o Gradle sincronizar. O download inicial das dependências demora.
3. Correr no OnePlus Nord 5 (USB debugging).
4. Confirmar que a app abre com o texto "Friends Reels" e um botão "Ativar serviço de acessibilidade".
5. Tocar no botão → devem abrir as definições de acessibilidade do Android. Encontrar "Friends Reels — Instagram Reader" e ativar.
6. Abrir `adb logcat` (ou o Logcat do Android Studio) com filtro `IGReaderService`. Ao ativar o serviço, deve aparecer a linha `InstagramReaderService connected`.
7. Se isto acontecer, o pipeline está verde e podemos avançar para PoC-2.

---

## 4. Decisões técnicas tomadas

| Decisão | Razão |
|---|---|
| Opção C (app externa + AccessibilityService) | Ver §2. |
| Kotlin + Jetpack Compose | Padrão moderno Android; menos código boilerplate; suporte oficial para tudo o que precisamos (UI vertical, ExoPlayer). |
| `minSdk 26` | Necessário para `dispatchGesture` estável e para não fragmentar a codebase com fallbacks antigos. Cobre 99%+ dos dispositivos ativos. |
| `targetSdk 35` (Android 15) | Play Store exige `targetSdk` recente. Android 16 do OnePlus corre estes apps sem problema. |
| Room + DataStore | Room para os Reels e mensagens descobertas; DataStore para preferências (modo de seleção, listas). |
| Media3/ExoPlayer | Reprodução dos vídeos do feed vertical. |
| Suportar EN **e** PT já desde o início | Utilizador vai testar com o IG em qualquer dos dois idiomas. Barato de fazer agora, caro depois. |
| Selectors centralizados num único ficheiro | Facilita atualizações quando a UI do IG muda. Será criado na primeira iteração PoC-2. |
| **Sem** login com password do IG | Requisito de segurança da spec §15. |
| Deep-links do IG (`instagram://reels_share?reel_id=…`, `instagram://direct/t/…`) | Preferir sempre deep-links sobre navegação por Accessibility para fallbacks de "Abrir no Instagram". Mais fiável e não depende de UI. |

---

## 5. Limitações conhecidas (registadas antecipadamente para gerir expectativas)

- **AccessibilityService é frágil a mudanças de UI do IG.** Vamos precisar de manutenção periódica quando a Meta lançar redesigns. Mitigado com selectors centralizados e suporte multi-idioma.
- **Reprodução do Reel diretamente na app externa:** o URL do Reel público (`https://www.instagram.com/reel/XXX/`) não é diretamente reproduzível num `ExoPlayer` — o IG serve o vídeo apenas para clientes autenticados. Alternativas: (a) reproduzir um preview via URL da thumbnail e o vídeo apenas ao "Abrir no Instagram", ou (b) tentar extrair o `media_url` via web scraping (frágil), ou (c) usar a Graph API (não aplicável a DMs). **Escolha por decidir no PoC-8.**
- **Ban risk operacional:** mesmo sem cliente modificado, se a AccessibilityService automatizar demasiado agressivamente pode disparar heurísticas de "comportamento não humano" da Meta. Mitigar com delays humanos entre ações, sync em background em pequenas rajadas, e nunca partilhar login com servidor terceiro.
- **Notificações push do IG não são intercetáveis** sem `NotificationListenerService`. Se quisermos deteção em tempo real de novos Reels, avaliamos essa via como complemento no PoC-2/3.
- **Fluxo de disparo das ações (PoC-5+):** usar sempre a **notificação persistente** ("Friends Reels") no shade. Tocar botões dentro da MainActivity funciona (o service traz o IG à frente automaticamente), mas alterna o foreground e algumas builds do IG podem reagir de forma inesperada. A notificação evita totalmente a troca de app.
- **Mensagens enviadas por nós:** a mesma DM pode conter mensagens que o utilizador enviou. Estas **não** devem entrar no feed nem em ações de reação (o IG não permite reagir às próprias mensagens em muitos casos). O sinal está em `message_content`: se contém o nó `sender_avatar` a mensagem foi recebida; se não, foi enviada por nós. Implementação no PoC-4.

---

## 6. Próximos passos concretos

**Estado dos PoCs após sessão 11:**

- ✅ PoC-1 — skeleton (compila, corre, a11y service ativa)
- ✅ PoC-2 — dump da árvore de acessibilidade (`ACTION_DUMP_TREE`, `ACTION_DUMP_ALL_WINDOWS`)
- ✅ PoC-3 — long-press dirigido ao bubble do Reel via `dispatchGesture`
- ✅ PoC-4 — direção RECEIVED/SENT validada em DM e em grupo (sessão 13). Sub-tarefa em aberto: identificar o **nome do membro humano** que partilhou cada Reel dentro de um grupo — precisa de dump da árvore de um bubble de grupo para encontrar o selector.
- ✅ PoC-5 — reagir ao 1.º Reel com ❤ e 😂 (filtra por RECEIVED por defeito)
- 🔲 PoC-6 — responder ao Reel (via "Responder" no menu popup + composer)
- 🔲 PoC-7 — extrair URL do Reel (menu não tem sempre "Copiar link"; alternativa: abrir Reel viewer / Reencaminhar)
- 🔲 PoC-8 — feed vertical, Room DB, MVVM

### 6.1 Próxima sessão — sub-task "remetente humano em grupo" + PoC-6

**Sub-task PoC-4 (grupos):**

Contexto: em DM 1-a-1 o remetente humano é o próprio título do header (`lastKnownConversationTitle`, ex. `'Pedro Sardoeira'`). Em grupo isto não chega — o header dá o nome do grupo (`'O Burro a Vaca e os Reis Magos'`) e precisamos de saber **qual dos membros partilhou cada Reel específico**. O `title_text` do container XMA continua a ser o autor do Reel *na plataforma IG* (não o remetente na DM), por isso essa via não serve.

Plano concreto:

1. Utilizador entra num grupo com pelo menos um Reel recebido visível.
2. Baixar shade → tocar "Dump" (`ACTION_DUMP_ALL_WINDOWS`).
3. Enviar o log completo entre `===== DUMP_ALL START =====` e `===== DUMP_ALL END =====` — precisamos ver o subtree de um `message_content` recebido: junto do `sender_avatar` costuma haver um `TextView` com o nome ou username do membro (candidatos prováveis a IDs: `direct_message_sender_name`, `message_content_sender_name`, `attribution_username`, ou o `header_title` como filho do bubble).
4. Assim que tivermos o ID, adicionar `IgSelectors.Thread.SENDER_NAME = "<id_real>"` e incluir `dmSender: String?` em `DmReelEntry`, populado durante `enumerateReels`.
5. Actualizar `LIST_REELS[i]` para logar também `dmSender=`.

Se o membro humano não aparecer como texto dentro do próprio bubble, plano B: usar o `contentDescription` do `sender_avatar` (algumas versões do IG expõem o nome aí, ex. `"Pedro Sardoeira"`) — capturar isso no mesmo dump.

**PoC-6 (responder):**

- Após long-press, o menu popup expõe `context_menu_item` com `contentDescription`/`text` correspondente a `IgSelectors.ContextMenu.ACTION_REPLY`.
- Fazer `performAction(ACTION_CLICK)` nesse item.
- Aguardar o composer (`row_thread_composer_edittext`) ganhar foco → `performAction(ACTION_SET_TEXT)` com o texto → localizar o botão de envio (`row_thread_composer_send_button` ou equivalente, a confirmar via dump) → click.
- Adicionar `ACTION_REPLY_FIRST_REEL` com filtro `Direction.RECEIVED` reutilizando o `findFirstReelBubble` do PoC-4.

### 6.2 A seguir

- **PoC-6 responder:** clique no `context_menu_item` cujo `contentDescription` está em `IgSelectors.ContextMenu.ACTION_REPLY`, seguido de escrever no `row_thread_composer_edittext` e enviar.
- **PoC-7 URL:** o menu popup nem sempre tem "Copiar link". Plano: (a) tocar no bubble para abrir o Reel viewer e localizar o botão Share/Copiar link lá; ou (b) usar Reencaminhar → Copiar link do share sheet.
- **PoC-8 UI:** só depois de termos os primitivos de leitura/ação validados.

---

## 7. Log de sessões

### 2025-08-28 — Sessão 1 (Ricardo + Copilot CLI)

- Análise integral da spec v2.
- Discussão das trade-offs entre Opção A (APK modificado / LSPosed) e Opção C.
- Decisão: Opção C.
- Limpeza do PoC anterior (código gerado por outra AI que ia direto para Opção C sem justificar).
- Recriação do projeto Android do zero: Kotlin, Compose, Gradle 8.10.2 + wrapper, AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.10.01, Room 2.6.1, Media3 1.4.1.
- Criação deste ficheiro `PROJECT_PROGRESS.md` com a documentação da Fase 0.
- Primeiro commit contém apenas o skeleton que abre no Android Studio e permite ativar a AccessibilityService — objetivo é validar o pipeline no PC do utilizador antes de avançar.

### 2025-08-28 — Sessão 3 (Ricardo + Copilot CLI)

- **PoC-2 concluído.** Utilizador correu o dump nos 5 ecrãs pedidos (Home, Direct/Inbox, Conversa, Reel visível, Long-press). Os dumps ficaram guardados em `docs/screen-dumps/2025-08-28-initial-mapping.txt`.
- **Mapeamento dos seletores centralizado** em `com.example.friendsreels.instagram.IgSelectors`. IDs de recursos do IG são independentes do idioma; strings localizadas ficam em conjuntos PT + EN.
- **Descoberta importante (Direct/Inbox em Compose):** o inbox já não expõe `resource-id`s nas rows. A identificação das conversas terá de ser feita por `contentDescription` que segue o formato `"<nome>, [não lidos, ]<preview> ·, <tempo>"`.
- **Descoberta importante (long-press vs OnePlus):**
  - Simular long-press por toque real aciona o *Portal de conteúdo* do OxygenOS.
  - Usar `AccessibilityNodeInfo.performAction(ACTION_LONG_CLICK)` diretamente no nó `message_content` deve contornar essa interceção. Vamos validar no próximo teste do utilizador.
- **Descoberta importante (menu de contexto):** após long-press aparecem em simultâneo (a) o painel de reações rápidas com 6 emojis (❤️ 😂 😮 😢 😡 👍) já com IDs identificados, e (b) o menu completo (Responder, Copiar link, Reencaminhar, Eliminar…) implementado em Compose. O menu completo ainda não foi totalmente capturado em nenhum dump por causa do timing da animação.
- **Novo broadcast implementado:** `com.example.friendsreels.ACTION_LONG_PRESS_FIRST_REEL` faz agora `performAction(ACTION_LONG_CLICK)` no primeiro `message_content` cuja subtree contém `message_content_portrait_xma_container` ou `message_content_generic_xma_container`, e dispara automaticamente um dump 1500ms depois. Isto resolve o problema de timing manual e (esperamos) contornar o Portal de conteúdo.
- **Próximo passo do utilizador:** correr o novo broadcast dentro de uma conversa com um Reel e enviar os logs do IGReaderService entre `===== DUMP START reason=after-longpress =====` e `===== DUMP END =====`. Com esse dump completo, ficamos com todos os labels do menu (Responder, Copiar link, ...) para PoC-5 (reagir) e PoC-6 (responder).

### 2025-08-28 — Sessão 4 (Ricardo + Copilot CLI)

- **PoC-3 primeira tentativa falhou:** ao correr `ACTION_LONG_PRESS_FIRST_REEL`, o `performAction(ACTION_LONG_CLICK)` no `message_content` **abriu o menu de personalização do fundo da conversa**, não o menu do Reel.
- **Causa identificada:** o `message_content` é um `FrameLayout` que ocupa **toda a largura da linha** (`[0,267][1080,318]`), enquanto a bolha do Reel (mensagem recebida) só ocupa metade esquerda do ecrã. Sem coordenadas, o `performAction` dispara no centro da bounding box → cai fora da bolha → IG interpreta como long-click no fundo da conversa.
- **Solução implementada:** substituir `performAction(ACTION_LONG_CLICK)` por `dispatchGesture(...)` centrado nas coordenadas do próprio container do Reel (`message_content_portrait_xma_container` ou `message_content_generic_xma_container`), com duração de 600ms:
  - 600ms é longo o suficiente para o IG considerar long-press (~500ms é o threshold do sistema).
  - Curto o suficiente para não acionar o *Portal de conteúdo* do OxygenOS, que precisa de um press mais longo.
- **Melhoria adicional:** o candidato é escolhido pelo `top` da bubble mais próximo do topo do ecrã (primeiro Reel visível de cima para baixo), independentemente de ser `portrait` ou `generic`.
- **Novos logs esperados:**
  - `LONG_PRESS: target kind=portrait author=... bounds=... center=(x,y)`
  - `LONG_PRESS: dispatchGesture accepted=true duration=600ms`
  - `LONG_PRESS: gesture completed`
  - Dump com `reason=after-longpress`

### 2025-08-28 — Sessão 5 (Ricardo + Copilot CLI)

- **PoC-3 com `dispatchGesture` funcionou:** o long-press caiu na bolha do Reel correto (`relatable_sayyz`), o IG entrou em modo "message actions" (`message_actions_container` presente) e o utilizador **confirmou visualmente** que apareceram simultaneamente:
  - a barra de reações rápidas (❤️ 😂 😮 😢 😡 👍),
  - o menu de contexto completo (Responder, Copiar link, Reencaminhar, Eliminar).
- **Descoberta importante:** o dump da janela ativa mostra `compose_context_menu` com altura 0 — isto significa que o menu **não está na janela principal do IG**. Está numa **janela popup separada** que o `rootInActiveWindow` não vê.
- **Solução implementada:** nova ação `ACTION_DUMP_ALL_WINDOWS` que usa `AccessibilityService.getWindows()` para enumerar todas as janelas visíveis (aplicação, IME, sistema, overlays) e dumpa cada uma. O dump automático após long-press passou a usar esta função.
- **Próximo passo do utilizador:** correr `ACTION_LONG_PRESS_FIRST_REEL` de novo dentro da conversa com Reel; enviar o `DUMP_ALL` completo. Com esses labels vamos poder fazer PoC-5 (clicar em ❤️/😂) e PoC-6 (clicar em "Responder").

### 2025-08-28 — Sessão 6 (Ricardo + Copilot CLI)

- **Menu de contexto totalmente capturado.** O `DUMP_ALL` mostrou 5 janelas; a WINDOW[3] é a popup do menu, com `context_menu_options_list` a envolver botões `context_menu_item` para: Responder, Adicionar sticker, Reencaminhar, Afixar, Eliminar para ti. **Nota:** para este Reel específico não apareceu "Copiar link" — vamos ter de descobrir o URL por outra via (abrir o Reel no viewer, ou "Reencaminhar" e cancelar). Fica para o PoC-7 (URL extraction).
- **`IgSelectors.ContextMenu` atualizado** com IDs reais do menu (`context_menu_options_list`, `context_menu_item`, `context_menu_item_label`, `context_menu_item_sub_label`) e labels PT+EN observados.
- **PoC-5 implementado (reagir com ❤️/😂):** duas novas ações broadcast na service:
  - `ACTION_REACT_HEART` — long-press no 1.º Reel, espera 2.1 s, procura o ImageView com `desc="❤Reação"` (ou variantes) em **todas** as janelas via `getWindows()`, e faz `performAction(ACTION_CLICK)`.
  - `ACTION_REACT_LAUGH` — igual para 😂.
- **UI de PoC na MainActivity:** botões para "Ativar acessibilidade", "Abrir Instagram", "Long-press no 1.º Reel + dump", "Dump de todas as janelas", "Reagir com ❤", "Reagir com 😂". Assim já se consegue testar sem `adb`.
- **Próximo passo do utilizador:**
  1. Testar visualmente as reações (❤️ e 😂) num Reel de uma conversa. Confirmar que a reação **aparece de facto** no IG (na bolha do Reel, canto inferior esquerdo) e sobrevive a fechar e reabrir a conversa.
  2. Reportar qual dos dois emojis funciona e qual não (para eu ajustar se necessário).
### 2025-08-28 — Sessão 7 (Ricardo + Copilot CLI)

- **Bug encontrado no primeiro teste de reação:** ao tocar num botão da nossa app, o foreground passa a ser `com.example.friendsreels`. A guarda no início do `longPressFirstReel` rejeitava a ação com "LONG_PRESS ignored: foreground is com.example.friendsreels, expected com.instagram.android".
- **Correção:** todas as ações broadcast passam agora por `runInInstagram { ... }`, que:
  1. Verifica se o IG já é a janela ativa; se sim, executa imediatamente.
  2. Caso contrário, lança `packageManager.getLaunchIntentForPackage("com.instagram.android")` com `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_REORDER_TO_FRONT` (isto retoma o IG **no último ecrã visitado**, incluindo a conversa aberta).
  3. Polls a cada 200 ms até 4 s até `rootInActiveWindow.packageName == com.instagram.android`.
  4. Quando o IG está foreground, corre a ação.
- **UX resultante:** o utilizador abre a conversa, volta à nossa app, toca em "Reagir com ❤", e a app traz automaticamente o IG à frente antes de fazer o gesto. Não é preciso `adb`.
- **Próximo passo do utilizador:** retestar. Se falhar, capturar Logcat (`IGReaderService`) — os logs vão mostrar "foreground is 'X', bringing Instagram to front" e depois "Instagram now in foreground" ou "gave up waiting".

### 2025-08-28 — Sessão 8 (Ricardo + Copilot CLI)

- **Bug encontrado na sessão 7:** o `runInInstagram` fazia `startActivity(launch)` com `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_REORDER_TO_FRONT`. Resultado: o Instagram vinha para a frente **mas na lista de conversas**, não na conversa que o utilizador tinha aberto.
- **Diagnóstico do utilizador (fundamental):** fez o teste manualmente — abriu conversa no IG → HOME → abriu IG pelo ícone → **restaurou a conversa**. Também abriu conversa → alternou para a nossa app pelo task switcher → voltou ao IG → **restaurou a conversa**. Só quando o botão dentro da app disparava `startActivity(launch)` é que o IG caía no inbox. Logo o problema estava no **flag**, não no comportamento normal do IG.
- **Causa raiz:** `FLAG_ACTIVITY_REORDER_TO_FRONT` reordena a activity dentro da task e, quando é aplicado à activity de launcher do IG (que é `singleTask`), a task é reduzida ao root — ou seja, cai no MainTabActivity, aba Direct/Inbox. O launcher do Android tapa no ícone com o intent sem esse flag, e por isso funciona.
- **Correção 1 (essencial):** manter apenas `FLAG_ACTIVITY_NEW_TASK` (o launcher intent já traz esse flag). Sem `REORDER_TO_FRONT` o IG mantém-se exactamente onde estava.
- **Correção 2 (UX melhor para MVP):** o service posta agora uma **notificação persistente** ("Friends Reels") com botões de ação (❤ / 😂 / Dump). Ao tocar num botão do shade, o Android fecha o shade e devolve o foco à app subjacente (o IG na conversa) — **sem passar pela nossa app**. Isto elimina completamente o problema de troca de foreground. Os botões dentro da MainActivity continuam a existir para debug e para uso via `adb`.
- **Rastreamento defensivo:** o `onAccessibilityEvent` agora captura o `header_title` das conversas abertas em `lastKnownConversationTitle`. Serve como base para diagnóstico e para o PoC-4 (identificar quem enviou cada Reel). Não é usado ainda para navegar de volta à conversa porque a Correção 1 já resolve isso; fica de reserva se algum flow futuro voltar a partir a restauração da conversa.
- **Nova constraint documentada (Requisitos, §1):** o utilizador também envia Reels aos amigos — o MVP tem de filtrar mensagens enviadas por nós. Diferenciação já mapeada: presença de `sender_avatar` dentro de `message_content` = mensagem recebida. Vai ser implementado no PoC-4.
- **Alterações concretas neste commit:**
  - `InstagramReaderService.kt`: remove `FLAG_ACTIVITY_REORDER_TO_FRONT`; adiciona `postControlNotification`/`cancelControlNotification`/`createNotificationChannel`/`pendingBroadcast`; adiciona tracking de `lastKnownConversationTitle` em `onAccessibilityEvent`.
  - `MainActivity.kt`: pede a permissão `POST_NOTIFICATIONS` no arranque (Android 13+).
  - `res/drawable/ic_notification.xml`: ícone monocromático simples para a notificação.
  - `res/values/strings.xml`: strings da notificação e novo texto de ajuda dos botões.
- **Próximo passo do utilizador:**
  1. Puxar o repo, correr no OnePlus.
  2. Aceitar o pedido de permissão de notificações (aparece ao abrir a app).
  3. Abrir uma conversa no IG com um Reel visível.
  4. Baixar a barra de notificações → tocar em ❤ ou 😂 na notificação **Friends Reels**. Não abrir a nossa app.
  5. Reportar: (a) se o IG mantém a conversa aberta, (b) se a reação aparece na bolha do Reel, (c) o Logcat do `IGReaderService` se algo falhar.

### 2025-08-28 — Sessão 9 (Ricardo + Copilot CLI)

- **Bug crítico encontrado no PoC-5:** o long-press estava a ser disparado **fora do ecrã**. Log do teste:
  - Bubble do `relatable_sayyz` reportada pelo `rootInActiveWindow`: `bounds=[1278,388][1723,1187] center=(1500,787)`.
  - Mesma bubble reportada pelo `windows[APPLICATION].root` (usado no dump): `b=[147,385][593,1187]`.
  - Ecrã tem 1080 pixels de largura. `x=1500` está a **420 pixels fora do ecrã** — o `dispatchGesture` era enviado mas o sistema descartava, portanto o menu de reações nunca abria e nada acontecia visualmente.
- **Diagnóstico:** no OnePlus Nord 5 / Android 16, o `rootInActiveWindow` está a devolver uma árvore alternativa em que `getBoundsInScreen` reporta coordenadas com offset em X de ~1131 pixels em relação aos pixels realmente renderizados. Não é claro se é bug do OxygenOS ou comportamento normal do Android 16, mas os dois caminhos (rootInActiveWindow vs windows[APPLICATION].root) devolvem valores diferentes para o **mesmo nó**. O caminho `windows[...]` bate certo com os pixels visíveis.
- **Fix:** o `longPressFirstReel` agora usa `findIgApplicationWindow()` (nova helper) para obter a janela de aplicação do IG a partir de `getWindows()`, e trabalha só com essa árvore. Cai de volta para `rootInActiveWindow` apenas se por algum motivo não conseguir encontrar a janela.
- **Guarda extra:** se o centro do bubble cair **fora dos bounds da janela do IG**, o service loga um WARNING e recusa disparar o gesto. Serve para termos evidência imediata no Logcat em vez de "nada aconteceu".
- **Confirmação do dump:** o dump `after-longpress` da sessão anterior mostra a thread no **estado normal** (sem `message_actions_container`, sem `creation_row_container`) — prova de que o long-press nunca chegou ao IG.
- **Nota sobre o double-tap do IG:** o utilizador lembrou que double-tap num Reel na DM aplica ❤ automaticamente. Guardamos essa informação como plano B para reações de coração: se a via do long-press+quick-reaction alguma vez falhar de novo, podemos dispatched um double-tap gesture sobre o bubble. Fica na back-pocket, para já mantemos o long-press porque é o único caminho que também suporta 😂 (e outros emojis).
- **Próximo passo do utilizador:** puxar o repo, correr, testar de novo os botões da notificação para ❤ e 😂. Os logs devem agora mostrar bounds dentro de 0..1080 em X. Se o menu abrir mas o emoji não for tocado, é problema seguinte a resolver.

### 2025-08-28 — Sessão 10 (Ricardo + Copilot CLI)

- **Novo teste no OnePlus** mostrou os primeiros logs pós-fix da sessão 9. Continuavam sem funcionar visualmente. Análise revelou **duas causas** encadeadas:
  1. **Janela em animação de entrada.** O runInInstagram detecta o IG como foreground ~235 ms depois do startActivity. Mas a task do IG está ainda a fazer o slide-in a partir da direita: o getBoundsInScreen da janela reporta left=1128 (em vez de 0), variando a cada tentativa. Como as bounds das crianças herdam esse offset, o gesto ia disparar em x aprox 1500 num ecrã de 1080 pixels — outra vez fora do ecrã. Prova: os bounds relativos ao topo/esquerda da janela (bubble.left - window.left) davam aprox 147, igual ao dump feito 2 s depois.
  2. **Bubble alvo semi-cortada.** O pure_hu_yaarrr estava quase todo por cima do RecyclerView (b=[147,267][593,297], altura de 30 px). A ordenação por top escolhia esse em vez do próximo bubble totalmente visível.
- **Correções desta sessão:**
  - Novo predicado isInstagramReady(): exige packageName==IG e igWindow.left == 0.
  - runInInstagram usa esse predicado antes de correr a action, tanto no atalho inicial como em cada iteração do pollInstagramForeground.
  - Aumentado FOREGROUND_POLL_MAX_RETRIES de 20 para 30 (aprox 6 s).
  - findFirstReelBubble descarta bubbles com altura inferior a 200 px (MIN_REEL_BUBBLE_HEIGHT_PX).
- **Próximo passo do utilizador:** repetir os testes pela notificação. Logs esperados: window settled + bounds do bubble dentro de 0..1080 em X.

### 2025-08-28 — Sessão 11 (Ricardo + Copilot CLI) — PoC-5 CONCLUÍDO ✅

- **Resultado do teste do utilizador:** os três botões (❤, 😂, long-press) **funcionaram os três** no OnePlus Nord 5. Reação aplicada visualmente no Instagram e persiste após fechar/reabrir a conversa.
- **Feedback do utilizador (registado para futuras iterações):**
  1. O fluxo está "um pouco lento". Latência actual: até 6 s de espera pela janela do IG a assentar + 600 ms de long-press + 1500 ms de settle antes de tocar no emoji. Tuning fica para mais tarde — provavelmente conseguimos reduzir o settle para 800-1000 ms.
  2. **A reação também acontece em Reels/posts que o próprio utilizador enviou.** Isto é comportamento aceite pelo Instagram (o IG permite reagir a media partilhado por nós próprios, ao contrário de mensagens de texto onde não permite). Como o código actual não filtra a direção da mensagem, reage a qualquer Reel visível — **este ponto reforça a necessidade do PoC-4 (identificação do remetente)** para o MVP não incluir Reels que o próprio utilizador enviou.
  3. Uma segunda reação com o mesmo emoji **remove** a reação; uma segunda reação com emoji diferente **substitui**. Comportamento nativo do IG, coerente com o que precisamos.
  4. A reação vai sempre para o Reel mais próximo do topo do ecrã — é o nosso `findFirstReelBubble` a ordenar por `top` ascendente. No MVP final o utilizador vai escolher no feed qual Reel receber a reação; para o PoC serve.
- **Estado dos PoCs:** ✅ PoC-1 (skeleton), ✅ PoC-2 (dump), ✅ PoC-3 (long-press), ✅ PoC-5 (reagir com ❤ e 😂).
- **Próximo passo prioritário:** **PoC-4 — identificação do remetente** (`sender_avatar` presente = recebida; ausente = enviada por nós). Com isto podemos:
  - Filtrar as próprias mensagens do feed futuro.
  - Devolver ao PoC-5 uma opção para ignorar mensagens enviadas por nós na hora de escolher o alvo.
- **A seguir:** PoC-6 (responder ao Reel via `context_menu_item` "Responder" + composer), PoC-7 (extrair URL do Reel).

### 2025-08-28 — Sessão 12 (Ricardo + Copilot CLI) — PoC-4 implementado

- **Novo ficheiro `instagram/DmReelEntry.kt`** com `enum Direction { RECEIVED, SENT }` e `data class DmReelEntry(index, kind, direction, reelAuthor, bounds, node)`. É o modelo partilhado que substitui o antigo `ReelTarget` interno da service. O `node` é sempre o container XMA (portrait ou generic), o alvo correcto para o long-press; `bounds` é uma cópia dos `getBoundsInScreen` capturada no momento da enumeração.
- **Nova função `enumerateReels(messageList)`** na `InstagramReaderService`:
  - Itera **todos** os `message_content` visíveis (não só o primeiro), o que é a base para a listagem completa e para o futuro sync.
  - Para cada bubble, resolve `Direction.RECEIVED` se o `message_content` contiver algum descendente com `id/sender_avatar`, `SENT` caso contrário.
  - Só devolve bubbles que contenham um dos containers de Reel (`message_content_portrait_xma_container` ou `_generic_xma_container`) — mensagens de texto/GIF/etc. são descartadas silenciosamente.
  - Extrai o `title_text` do container XMA como `reelAuthor` (o *username* de quem postou o Reel, não de quem o partilhou na DM).
- **`findFirstReelBubble` refactorada** para consumir `enumerateReels` e aceitar `onlyDirection: Direction? = Direction.RECEIVED`. Mantém o filtro `MIN_REEL_BUBBLE_HEIGHT_PX` (200 px) e a escolha do `bounds.top` mais pequeno. `longPressFirstReel` passa `onlyDirection = RECEIVED` se o toggle "Ignorar Reels enviados por mim" estiver ligado, `null` caso contrário.
- **Novo `ACTION_LIST_REELS`** exposto na notificação (novo botão "Listar") e no ecrã da app ("Listar Reels na conversa (log)"). Loga um resumo `LIST_REELS: found N Reel bubble(s) — received=X sent=Y conversation='<titulo>'` seguido de uma linha por Reel (`LIST_REELS[i]: dir=... kind=... author=... bounds=...`). Consome-se em `adb logcat -s IGReaderService:I`.
- **Preferência partilhada `friends_reels_prefs.ignore_sent_reels`** (default `true`) lida pela service em `isIgnoreSentEnabled()` e escrita pelo `Switch` na `MainActivity`. Estado sobrevive a reinstalação parcial (limpa se desinstalada).
- **UI actualizada** (`MainActivity`): novo `Switch` "Ignorar Reels enviados por mim" com legenda explicativa; novo botão "Listar Reels". Strings acrescentadas em `res/values/strings.xml` (`btn_list_reels`, `toggle_ignore_sent`, `toggle_ignore_sent_help`, `notif_action_list`).
- **Comportamento esperado no OnePlus:**
  - Um dump numa conversa mista deve mostrar todos os bubbles com a direção correcta.
  - Reagir com ❤ num ecrã cujo Reel de topo foi enviado por nós já não reage a esse Reel — vai para o próximo `RECEIVED` visível. Log do target confirma `direction=RECEIVED`.
  - Desligar o switch → volta ao comportamento do PoC-5 (reage ao 1.º Reel, seja qual for a direção).
- **Grupos:** ainda por dumpar. O `sender_avatar` deve estar presente em qualquer mensagem recebida (nossa hipótese); se em grupos aparecer também um label do nome do remetente humano dentro do `message_content`, capturaremos esse selector numa sessão futura para identificar quem partilhou o Reel em grupo. Fica em aberto para a próxima sessão de testes.
- **Ficheiros alterados neste commit:**
  - `app/src/main/java/com/example/friendsreels/instagram/DmReelEntry.kt` (novo).
  - `app/src/main/java/com/example/friendsreels/service/InstagramReaderService.kt` (enumerateReels, listReels, novo `ACTION_LIST_REELS`, `PREF_IGNORE_SENT`, filtragem por direção, notificação com botão "Listar").
  - `app/src/main/java/com/example/friendsreels/MainActivity.kt` (Switch de ignore-sent + botão Listar Reels).
  - `app/src/main/res/values/strings.xml` (strings novas).
- **Próximo passo do utilizador (validação PoC-4):**
  1. Pull, correr no OnePlus.
  2. Abrir uma conversa com Reels enviados **e** recebidos misturados.
  3. Baixar o shade → tocar "Listar" → confirmar Logcat (`adb logcat -s IGReaderService:I`) com as linhas `LIST_REELS[i]: dir=...`.
  4. Testar ❤ com o switch ligado (default) e depois desligado; reportar se o alvo bate certo.
  5. Repetir 3 num grupo e capturar o dump (`Dump`) para vermos que labels adicionais existem por bubble.

### 2025-08-28 — Sessão 13 (Ricardo + Copilot CLI) — PoC-4 validado no OnePlus

- **Utilizador testou no OnePlus Nord 5** e enviou o log em `docs/screen-dumps/ignore sent and group.txt`.
- **DM 1-a-1 `Pedro Sardoeira`** (3 Reels na visível: 1 recebido + 2 enviados):
  - `LIST_REELS: found 3 Reel bubble(s) — received=1 sent=2 ignoreSent=true`
  - Autores originais no IG (não confundir com o remetente da DM): `melqdy_1`, `iconic_cs2`, `rust.pro.me`. Foi este ponto que o utilizador levantou — confirmado que **é o autor do Reel na plataforma** (`title_text` do XMA container), útil para deduplicação e futuro feed. O **remetente humano na DM** em 1-a-1 é o próprio header (`lastKnownConversationTitle`, `'Pedro Sardoeira'`).
  - Só existiam Reels SENT no ecrã visível de uma tentativa → serviço recusou correctamente: `LONG_PRESS: no eligible Reel bubble found (ignoreSent=true)` (duas ocorrências consecutivas, comportamento esperado).
  - Depois de aparecer um recebido visível, ❤ funcionou: `target index=1 kind=portrait direction=RECEIVED author=pure_hu_yaarrr` seguido de `REACT: performAction(ACTION_CLICK) on emoji '❤' returned true`.
- **Grupo `O Burro a Vaca e os Reis Magos`**:
  - Primeiro scroll: só SENT visíveis (3), listagem devolveu `received=0 sent=3` com autores `yuiki.hanegawa`, `wascemal` e um `generic` sem `title_text` (`author=?`) — isso é normal: em containers `generic` o `title_text` nem sempre está presente. Não é bug — o fallback já regista `author=?` no log.
  - Segundo scroll: 3 recebidos consecutivos (`ryankellycomedy`, `adamraqeem`, `urokowatch`). Direção correcta.
- **Conclusão do PoC-4:** direção `RECEIVED`/`SENT` está **validada** em DM 1-a-1 e em grupo. Toggle "Ignorar Reels enviados por mim" respeita a filtragem tanto na listagem (implicitamente) como no target das reações.
- **Ponto em aberto (sub-task 6.1):** identificar o **membro humano** que partilhou cada Reel dentro de um grupo. Ainda não temos o dump da árvore de um bubble de grupo, por isso desconhecemos qual `resource-id` (ou `contentDescription` do `sender_avatar`) expõe o nome do membro. Fica para a próxima sessão de testes — dump completo de um grupo com pelo menos um Reel recebido.
- **Nenhum código alterado nesta sessão** — só documentação (`Estado atual`, §6 e este log). O flag do PoC-4 muda para ✅ com nota de sub-task.
