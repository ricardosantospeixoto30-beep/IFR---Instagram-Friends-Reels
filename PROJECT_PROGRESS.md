# PROJECT_PROGRESS — Friends Reels Inbox

> Ficheiro cumulativo de acompanhamento do projeto, conforme exigido pela spec §19.
> Atualizar sempre que houver decisões, investigação, testes ou mudanças relevantes.

---

## Estado atual

**Fase atual:** Fase 1 (PoC → MVP). **Sessão 37** — enriquecimento de URL on-demand (feed pede URL sozinho quando não tem) + seleção de conversas (spec §8) totalmente implementada. Bug do E7 confirmado corrigido em device na s36.
**Última atualização:** 2025-08-30 (sessão 37)
**Arquitetura escolhida:** Opção C — app externa Android + `AccessibilityService`. Manter (§9 confirma que é a via mais segura).
**HEAD actual:** `build=s37`.

### Como continuar na próxima sessão (quick start)

1. **Pull** do repo. Confirmar `Action receiver registered (build=s37 ...)`.
2. **Ler primeiro:** esta secção "Estado atual", §6 "Próximos passos", §7 log, §8 bateria de testes (F/G).
3. **Ficheiros-chave:**
    - `service/InstagramReaderService.kt` — motor a11y, batching, navegação, `enrichReelUrl(reelId)` novo. Prefs: `PREF_IGNORE_SENT`, `PREF_INVERT_SWIPE` (escondida), `PREF_SELECTION_MODE` (novo).
    - `ui/feed/FeedScreen.kt` — VerticalPager com WebView inline. Placeholder com botão "🔗 Preparar Reel" quando o Reel não tem URL.
    - `ui/feed/FeedViewModel.kt` — filtro por selection mode (INCLUDE_ONLY / EXCLUDE_SELECTED / NONE) + `requestUrlEnrichment(reelId)`.
    - `ui/settings/SettingsActivity.kt` + `SettingsViewModel.kt` — UI real de selecção de conversas com radio (modo) + lista de threads com checkboxes.
    - `ui/player/EmbedPlayer.kt` — helpers partilhados WebView.
    - `data/TrackedThreadEntity.kt`, `TrackedThreadDao.kt` — nova entidade Room v4 para spec §8.
    - `instagram/IgSelectors.kt` — IDs/labels do IG.
    - Dumps: `docs/screen-dumps/feed.txt` (última corrida s36 — F1-F5 todos ok).
4. **Constraints:**
    - Testes só no OnePlus Nord 5 / Android 16.
    - macOS deste ambiente não tem Android SDK, só validar sintaxe com kotlinc.
    - Cada refactor visível deve bumpar `BUILD_TAG`.
5. **UX actual em device:**
    - **Home:** protagonismo ao `▶ Abrir o meu feed`. Descoberta (🔍 conversa aberta, 📥 histórico). Configuração (a11y toggle, abrir IG, ⚙ Definições).
    - **Feed:** full-screen VerticalPager. **Auto-play inline** por page. Chip único de reacção. Menu ⋮ com 3 opções. Reels sem URL mostram placeholder com **"🔗 Preparar Reel"** que dispara enrichment automático (nav+scroll+viewer+share+copy+backfill).
    - **Definições:** toggle "Ignorar Reels enviados" + **secção "Filtrar conversas no feed"** (3 radios: Ver tudo / Apenas selecionadas / Excluir selecionadas + lista de threads descobertas com checkboxes e contagem de Reels) + Ferramentas de diagnóstico.
    - **Notificação:** 3 botões (🔍 🔗 ▶).
6. **Limitações conhecidas:**
    - Matching por `reelAuthor` — 2 Reels do mesmo criador na mesma conversa colidem (top-most vence).
    - Cap de 20 scrolls no `locateReelWithScroll`.
    - Enrichment on-demand faz uma round-trip completa no IG (nav → scroll → viewer → share → copy). Pode demorar 5-8s por Reel — utilizador deve deixar acontecer sem tocar.
    - Consulta de respostas anteriores dentro da app (spec §5) — não temos sync.
    - Reacção "actual" só reflecte as que corremos via app.
    - `threadTitle` como chave — se o utilizador renomear um grupo, a selecção perde-se para essa thread (aparece como thread nova na lista descoberta).

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

> **Nota:** esta lista é o planeamento INICIAL da Fase 1. O estado real de execução (que evoluiu para PoC-8 com iterações 1-3 e PoC-9) está em **§6 "Próximos passos concretos"**. Cada iteração produziu um resultado registado em §7.

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
- **Reprodução do Reel diretamente na app externa** *(decidido na sessão 30-32, PoC-8 iter 3 parte C)*: o URL do Reel público (`https://www.instagram.com/reel/XXX/`) não é reproduzível num `ExoPlayer` — o IG serve o vídeo apenas para clientes autenticados. Solução adoptada: `ReelPlayerActivity` com WebView, `toEmbedUrl()` reescreve o URL para o embed oficial (`.../reel/<code>/embed`) que não exige login, e um `WebViewClient` custom intercepta `instagram://` / `intent://` para evitar o `ERR_UNKNOWN_URL_SCHEME`. Injecção JS força autoplay (unmuted → muted como fallback). Fallback nativo (**"↗ Abrir no Instagram nativo"**) fica sempre disponível.
- **Ban risk operacional:** mesmo sem cliente modificado, se a AccessibilityService automatizar demasiado agressivamente pode disparar heurísticas de "comportamento não humano" da Meta. Mitigar com delays humanos entre ações, sync em background em pequenas rajadas, e nunca partilhar login com servidor terceiro.
- **Notificações push do IG não são intercetáveis** sem `NotificationListenerService`. Se quisermos deteção em tempo real de novos Reels, avaliamos essa via como complemento no PoC-2/3.
- **Fluxo de disparo das ações (PoC-5+):** usar sempre a **notificação persistente** ("Friends Reels") no shade. Tocar botões dentro da MainActivity funciona (o service traz o IG à frente automaticamente), mas alterna o foreground e algumas builds do IG podem reagir de forma inesperada. A notificação evita totalmente a troca de app.
- **Mensagens enviadas por nós:** a mesma DM pode conter mensagens que o utilizador enviou. Estas **não** devem entrar no feed nem em ações de reação (o IG não permite reagir às próprias mensagens em muitos casos). O sinal está em `message_content`: se contém o nó `sender_avatar` a mensagem foi recebida; se não, foi enviada por nós. Implementado no PoC-4 (sessão 12).
- **Identificar o membro remetente em grupos (limitação da UI do IG, validada 2025-08-28 sessão 14):** no ecrã da conversa a árvore de acessibilidade **não expõe o nome/username do membro do grupo que partilhou o Reel**. O `sender_avatar` só tem `contentDescription="Foto de perfil"` (constante), não há qualquer `TextView` com o nome dentro do `message_content`, e o menu de contexto pós long-press só expõe data/hora. Consequências:
    - No feed do MVP, Reels partilhados em grupo aparecem como "de \<nome do grupo\>", não "de \<membro\>".
    - Se quisermos mais tarde o membro individual, opções: (a) tocar no `sender_avatar` para abrir o perfil e ler o username (troca de ecrã), (b) pixel-hash do avatar contra a lista de membros do grupo. Nenhuma via é trivial; fica em backlog.
- **Ações executam sempre com o IG em foreground.** O `dispatchGesture` opera nos pixels renderizados, portanto não é possível reagir/responder em background enquanto o utilizador vê Reels na nossa app. Mitigação implementada em PoC-8 iter 3 parte A: **batching**. O feed regista as intenções do utilizador (❤, 😂, resposta) numa fila local (`pending_actions`); quando o utilizador toca **"Aplicar N acções no Instagram"** a app traz o IG à frente uma única vez e o service percorre a fila sequencialmente. PoC-9 iter 1 (sessão 33) alarga isto a **múltiplas conversas** num único pass — o executor agrupa steps por thread e navega automaticamente entre elas pela inbox.

---

## 6. Próximos passos concretos

**Estado dos PoCs após sessão 34:**

- ✅ PoC-1 — skeleton (compila, corre, a11y service ativa)
- ✅ PoC-2 — mapeamento inicial (dumps em `docs/screen-dumps/`)
- ✅ PoC-3 — long-press dirigido ao bubble via `dispatchGesture`
- ✅ PoC-4 — direção RECEIVED/SENT validada em DM 1-a-1 e em grupo. Nome do membro em grupo capturado via `sender_username_or_fullname` do Reel viewer.
- ✅ PoC-5 — reagir com ❤ e 😂 (filtra por RECEIVED por defeito)
- ✅ PoC-6 — responder ao 1.º Reel recebido com texto mock "👀"
- ✅ PoC-7 — copiar URL do Reel via viewer → Partilhar → Copiar ligação → clipboard bridge (`ClipboardCaptureActivity`)
- ✅ PoC-8 iter 1 — Room + `ACTION_DISCOVER_REELS` + feed vertical
- ✅ PoC-8 iter 2 — integração PoC-7↔PoC-8: URL + `dmSender` persistidos, dedup 3-way (promote → insert → backfill)
- ✅ PoC-8 iter 3 parte A — batching: `pending_actions` + enfileirar/cancelar por card + executor `ACTION_APPLY_PENDING` com delays por-kind + notificação de 3 botões (🔍 🔗 ▶) + `contentIntent` para o feed
- ✅ PoC-8 iter 3 parte B — `ACTION_DISCOVER_REELS_HISTORY` com `ACTION_SCROLL_BACKWARD` (fallback: swipe DOWN via gesture); stop em 3 scrolls empty ou cap de 30
- ✅ PoC-8 iter 3 parte C — player embed (`.../reel/<code>/embed`) com JS injection para autoplay (unmuted → fallback muted); WebViewClient intercepta `instagram://` e `intent://` redirects; overlay de erro com fallback nativo
- ✅ PoC-9 iter 1 — batching navega entre conversas via `header_title` na inbox (validado em device na s34)
- ✅ PoC-8 iter 4 — batching localiza o Reel específico por `reelAuthor` + `ACTION_SCROLL_BACKWARD` até 20× + fallback swipe DOWN (validado em device s35).
- ✅ s35 — feed alinhado com a visão (VerticalPager, reply real, estados por Reel, 3-pontinhos, Settings). Validada em device com nuances registadas.
- ✅ s36 — auto-play inline (WebView por page) + fix E7 (isInboxVisible estrito) + chip único de reacção + limpeza do menu e Settings. **Validada em device: F1-F5 todos passaram, F6 sanity ok.**
- 🚧 **s37 — enriquecimento de URL on-demand + seleção de conversas (spec §8):**
    - **`ACTION_ENRICH_REEL_URL`** com extra `reel_id`: service compõe nav → locate → viewer tap → share → copy → backfill numa única round-trip por Reel. Feed dispara automaticamente quando o utilizador toca "🔗 Preparar Reel" no placeholder.
    - **Seleção de conversas**: novo `TrackedThreadEntity` (Room v4), `PREF_SELECTION_MODE` com 3 valores (NONE / INCLUDE_ONLY / EXCLUDE_SELECTED), UI de radios + checkboxes em Definições, filtro no `FeedViewModel.reels`.
    - Aguarda validação em device.

### 6.1 Próxima sessão — validar s37 (enrichment + selecção)

**Bateria G1-G3** actualizada em §8. TL;DR:

- **G1 — Enrichment on-demand:** entrar num Reel sem URL, tocar **"🔗 Preparar Reel"**. IG abre, navega para a conversa, faz scroll até ao Reel, abre viewer, copia URL, volta. Feed passa a mostrar o vídeo em auto-play.
- **G2 — Selecção "Apenas selecionadas":** definir modo, escolher 1 thread, voltar ao feed. Só devem aparecer Reels dessa thread.
- **G3 — Selecção "Excluir selecionadas":** definir modo, escolher 1 thread para excluir, voltar ao feed. Reels dessa thread desaparecem, restantes ficam.

### 6.2 Backlog (roadmap)

- **Sync de reacção actual** (spec §7 "Reagido = reacção existe agora") — passo de refresh que percorre a conversa e lê o `message_reactions_pill_container`, cruzando com `pending_actions.DONE`. Também serve para consultar reacções que o utilizador fez directamente no IG.
- **Match estrito por URL** — só se o matching por `reelAuthor` mostrar colisões reais em uso.
- **Indicador visual de direcção no feed** — pré-requisito para re-expor o toggle "Inverter direção do swipe".
- **UX polish** — thumbnails/preview no feed, animação de loading enquanto o WebView arranca.
- **Tuning de latência** — reduzir vários `_SETTLE_MS` progressivamente.
- **Enrichment em batch** — botão em Definições ou notif que enfileira o enrichment para TODOS os Reels sem URL (ver §6.4 s37 nota — pode ser aditivo mas UX é: espera muito).
- **Deep-link `instagram://direct/t/<thread_id>`** — só se o fallback por título mostrar problemas.

### 6.3 Alternativas arquitecturais (investigação da s36)

Utilizador levantou a hipótese na s36 depois de ver uma publicidade da app **Socialite** — reclama que permite ver Reels/Stories/Shorts de contas que o utilizador não segue. Se for possível sem risco de ban, poderia justificar re-abrir a Opção A/B da spec (integração dentro do IG oficial). Pesquisa completa em **§9 abaixo**. TL;DR:

- **A "Socialite" publicitada é provavelmente o "SocialLite" iOS**, que faz o OPOSTO (bloqueia Reels/Shorts). Arquitectura: WKWebView + JS que remove elementos de UI. **Não** é acesso privilegiado a contas alheias — apenas mostra o IG web normal focado sem distracções.
- **A frase "ver contas que não segues" é enganosa.** IG web mostra Reels públicos de qualquer conta a qualquer utilizador — nada de especial. Qualquer app que reclame acesso a DMs/Stories de contas privadas alheias é quase certamente credential harvester.
- **A visão original da spec (Opção A pura) NÃO é viável sem risco significativo.** Modding do APK = ban imediato. Private API (instagram4j etc.) = alto risco, Meta persegue com C&D (caso Barinsta 2021). WebView wrapper = risco baixo mas UX degradada.
- **A nossa Opção C actual (AccessibilityService) é objectivamente a via mais segura para a conta do utilizador** — Meta não consegue detectar server-side. Manter como direcção principal.

### 6.4 PoC alternativa (opcional, low-priority)

Se algum dia a manutenção do a11y ficar cara demais (i.e. IG partir a UI em cada release), a alternativa a experimentar é a **Opção A.2 via WebView wrapper (padrão Frost-for-Facebook)**:

- WebView embed com `m.instagram.com`
- Utilizador loga-se dentro da nossa app (session isolada da app nativa)
- JavaScriptInterface + injecção JS para extrair URLs de vídeo do DOM das DMs
- Custo: perde-se sessão partilhada (dupla autenticação), UX de swipe fica mais artificial (VerticalPager a alternar de página no WebView), e o IG web não expõe tudo o que a app expõe (composição de mensagens em DMs pode ser limitada)

Este trabalho fica em backlog até haver sinal claro de que a a11y não escala.

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

### 2026-08-28 — Sessão 14 (Ricardo + Copilot CLI) — PoC-4 fechado, limitação de grupos confirmada

- **Dump completo do grupo capturado** (`docs/screen-dumps/ignore sent and group.txt`, linhas 60→251 — DUMP_ALL antes e depois do long-press).
- **Long-press num Reel recebido do grupo funcionou:** `target index=2 kind=portrait direction=RECEIVED author=urokowatch bounds=[147,267][593,776]` → menu de contexto abriu na WINDOW[3] popup normalmente.
- **Análise da árvore do bubble em grupo (WINDOW[3] APPLICATION, id=1436):** cada `message_content` recebido tem exactamente estes filhos:
  - `forwarding_shortcut_button` (desc="Reencaminhar mensagem")
  - `save_to_collection_shortcut_button` (desc="Guardar numa coleção")
  - `sender_avatar` (**desc="Foto de perfil"** — constante, não personalizada com nome do membro)
  - `message_content_portrait_xma_container`
    - `profile_attribution_picture` (imagem do autor original do Reel no IG)
    - `title_text` (username do autor original do Reel no IG — não o membro do grupo)
  - `message_reactions_pill_container` (opcional)
- **Conclusão:** na versão actual do IG a a11y layer **não expõe** o nome/username do membro que partilhou um Reel específico dentro de um grupo. O menu de contexto pós long-press também só tem data/hora (`sub_label="29/07, 8:05 DA TARDE"`), sem nome. Registei esta limitação em §5 e uma nota permanente em `IgSelectors.Thread.SENDER_AVATAR`.
- **Alternativas futuras (backlog, não urgentes):**
  - Tocar no `sender_avatar` para abrir o perfil do membro e ler o username (troca de ecrã).
  - Pixel-hash do avatar visível contra a lista de membros do grupo (obtida uma vez ao entrar).
- **Implicação no MVP:** no feed, Reels partilhados em grupo apresentam-se como "de \<nome do grupo\>" (`lastKnownConversationTitle`). Reels de DM 1-a-1 continuam a mostrar o remetente humano (que também vem do `lastKnownConversationTitle`, sendo o próprio interlocutor).
- **Decisão:** PoC-4 fica **fechado**. Próximo passo prioritário: **PoC-6 (responder ao Reel)** — plano em §6.1. Ainda precisamos de um dump extra do composer com texto escrito (a captar na próxima sessão de testes) para confirmar o id/bounds do botão de envio.
- **Nenhum código alterado nesta sessão** — apenas documentação (`Estado atual`, §5, §6.1, este log) + comentário permanente em `IgSelectors.Thread.SENDER_AVATAR`.
- **Melhoria de workflow acordada:** o utilizador vai enviar o dump completo logo à primeira em vez de o partilhar por partes.

### 2026-08-28 — Sessão 15 (Ricardo + Copilot CLI) — PoC-6 implementado

- **Nova sub-classe `AfterLongPress.ReplyWithText(text: String)`** encadeada ao long-press existente. Reaproveita 100% do PoC-3/5 até ao momento em que o menu popup abre.
- **Novo `ACTION_REPLY_FIRST_REEL_MOCK`** (broadcast + botão 👀 na notificação e no ecrã da app). Texto de teste hardcoded em `MOCK_REPLY_TEXT = "👀"` — no MVP final ficará dinâmico (input do utilizador no feed).
- **Pipeline `openReplyAndSend(text)`:**
  1. Procura em `getWindows()` um `context_menu_item` cujo `contentDescription` está em `ContextMenu.ACTION_REPLY` (`"Responder"` / `"Reply"`) e faz `performAction(ACTION_CLICK)`. Se não achar → `dumpAllWindows("reply-no-menu")` para vermos porquê.
  2. Aguarda `COMPOSER_SETTLE_MS = 900 ms`, procura `row_thread_composer_edittext`, faz `performAction(ACTION_SET_TEXT, Bundle{ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE=text})`. `ACTION_SET_TEXT` funciona no Compose EditText do IG sem precisar de IME.
  3. Antes de escrever, verifica também a presença do `message_composer_reply_bar_container` (preview "A responder a…") como sinal positivo do fluxo estar em modo reply — só loga um WARN se estiver ausente, não bloqueia.
  4. Aguarda `SEND_SETTLE_MS = 500 ms` (o strip voice/gallery vira botão Send assim que há texto).
  5. Procura o botão Send por lista de candidatos `IgSelectors.Thread.COMPOSER_SEND_BUTTON_CANDIDATES` (`row_thread_composer_send_button`, `..._send`, `composer_send_button`, `send_button`) + fallback por `contentDescription` em `COMPOSER_SEND_LABELS = {"Enviar", "Send"}`. Se nenhum bater → `dumpAllWindows("after-set-text")` para capturar o id real.
  6. Se o nó encontrado for `!isClickable`, sobe até 5 níveis à procura de um ancestral clicável antes de chamar `performAction(ACTION_CLICK)` (comum em Compose onde o icon é filho de um Button clicável).
- **Selectors adicionados em `IgSelectors.Thread`:** `COMPOSER_SEND_BUTTON_CANDIDATES` (lista), `COMPOSER_SEND_LABELS` (set), `COMPOSER_REPLY_BAR_CONTAINER = "message_composer_reply_bar_container"`.
- **UI/Notificação:** botão 👀 (`btn_reply_reel` / `notif_action_reply`). No shade fica junto do ❤ e 😂 (Android decide quantos mostrar visíveis; todos estão sempre acessíveis via expand).
- **Nenhum uso do IME nem do clipboard** — `ACTION_SET_TEXT` é uma API a11y directa. Se o IG algum dia bloquear isto, plano B fica documentado em §6.1 do commit anterior (clipboard + `ACTION_PASTE`).
- **Ficheiros alterados:**
  - `IgSelectors.kt` — selectors novos do composer send + reply bar.
  - `InstagramReaderService.kt` — `AfterLongPress.ReplyWithText`, `openReplyAndSend`, `typeInComposer`, `clickSendButton`, `findClickableAncestor`, `ACTION_REPLY_FIRST_REEL_MOCK`, timings, botão na notificação.
  - `MainActivity.kt` — botão "Responder com 👀 ao 1.º Reel".
  - `strings.xml` — `btn_reply_reel`, `notif_action_reply`.
  - `PROJECT_PROGRESS.md` — estado atual, §6.1 e este log.
- **Próximo passo do utilizador:** teste no OnePlus (ver §6.1) e enviar Logcat + eventualmente o `after-set-text` DUMP_ALL se o botão Send não for encontrado à primeira. Se tudo funcionar, PoC-6 fecha e passamos ao PoC-7 (extrair URL do Reel).

### 2026-08-28 — Sessão 16 (Ricardo + Copilot CLI) — PoC-6 validado ✅ + PoC-7 exploração inicial

- **Utilizador testou PoC-6 no OnePlus:** o fluxo completo `long-press → Responder → composer → 👀 → Send` **funcionou à primeira**. Latência subjectiva descrita como "um pouco lenta" (~3.5 s de settle total), mesmo perfil da reacção. Nenhum dump `after-set-text` foi necessário — o probe de ids acertou logo.
- **Latência: plano futuro.** Vamos afinar os settles (600 → 800/600/300) só depois de fecharmos o PoC-8. Já é o tuning esperado, código foi conservador de propósito para o primeiro teste passar.
- **Question do utilizador — "dá para acontecer por trás enquanto vejo Reels?"** — Resposta e decisão em §5 "Ações executam sempre com o IG em foreground": não em tempo real (o `dispatchGesture` só funciona no foreground), mas a solução prevista é **batching** no PoC-8 (fila local + flush único no fim). Ficou registado como limitação + mitigação.
- **PoC-7 arranque:** implementei a ação exploratória `ACTION_OPEN_REEL` para abrir o Reel viewer nativo do IG a partir da conversa.
  - `openFirstReelViewer()` reutiliza `findFirstReelBubble(RECEIVED)` do PoC-4.
  - `dispatchGesture` com `TAP_DURATION_MS = 80 ms` centrado no container XMA (não usa long-press porque o objectivo é abrir o viewer, não o menu de contexto).
  - Aguarda `REEL_VIEWER_SETTLE_MS = 2000 ms` para o Reel viewer carregar o vídeo/controls e faz `dumpAllWindows("after-reel-tap")` automaticamente.
  - Guarda a mesma protecção do PoC-5/6: se o centro do bubble cair fora do IG window, recusa disparar.
- **Novo botão UI + notificação:** "Abrir" (notificação, request 6) / "Abrir 1.º Reel no viewer (+ dump)" (ecrã da app). Broadcast `ACTION_OPEN_REEL`.
- **Ficheiros alterados:**
  - `InstagramReaderService.kt` — `openFirstReelViewer`, novas constantes `TAP_DURATION_MS`/`REEL_VIEWER_SETTLE_MS`, broadcast `ACTION_OPEN_REEL`, botão na notificação.
  - `MainActivity.kt` — botão "Abrir 1.º Reel no viewer".
  - `strings.xml` — `btn_open_reel`, `notif_action_open`.
  - `PROJECT_PROGRESS.md` — estado atual, §5 (batching), §6.1 (plano para completar PoC-7), este log.
- **Próximo passo do utilizador (para fechar PoC-7):**
  1. Abrir uma conversa com um Reel recebido visível.
  2. Baixar shade → tocar **Abrir**.
  3. Deixar o Reel viewer abrir; após ~2 s o dump é automático.
  4. Enviar o log completo entre `===== DUMP_ALL START reason=after-reel-tap =====` e `===== DUMP_ALL END =====` (tal como o dump do grupo — envio de raspão logo à primeira).
- Com esse dump ficamos a saber que botões o viewer expõe (Share, ⋮, Copiar link, etc.) e implemento `ACTION_COPY_REEL_URL` na sessão seguinte.

### 2026-08-28 — Sessão 17 (Ricardo + Copilot CLI) — Reel viewer mapeado + PoC-7 iteração 2

- **Dump `docs/screen-dumps/reel dump.txt` analisado.** O viewer nativo do IG vive **todo dentro da WINDOW[3] APPLICATION** — não abre em popup, portanto `rootInActiveWindow` chega. Estrutura mapeada:
  - Header: botão retroceder, `sender_profile_pic`, `sender_username_or_fullname` (text="Pedro Sardoeira"), `sender_timestamp` (text="Há 3 h"), botão "Criar um reel".
  - Vídeo: `clips_video_container` dentro de `clips_media_component`.
  - Strip vertical direito (`clips_ufi_component`): `like_button`, `like_count`, `comment_button`, `desc="Republicar"`, **`direct_share_button` desc="Partilhar"**, `ufi_text_component` (repartilhas), `save_button`, **`clips_ufi_more_button_component` desc="Mais"**, `media_album_art_button` desc="Áudio".
  - Info do autor original: `clips_author_username` text="relatable_sayyz".
  - Reply composer: `reply_bar_edittext` text="Responde a Pedro Sardoeira", 3 emojis rápidos (`item_emoji`), `reply_bar_reaction_sheet_button`.
  - `SeekBar` `id/scrubber` (barra de progresso do vídeo).
- **Descoberta bónus para o PoC-4 em grupos:** `sender_username_or_fullname` mostra o **nome de quem partilhou o Reel na DM** (em 1-a-1 foi "Pedro Sardoeira"). Em grupos, hipoteticamente vai expor o nome do membro individual. Isto abre uma via viável para o problema registado em §5 — sem precisarmos de pixel-hash: assim que estivermos com o Reel viewer aberto, temos o nome do remetente humano. Fica como possível melhoria futura do PoC-4 (não urgente).
- **Todos os selectors do viewer centralizados em `IgSelectors.ReelViewer`.**
- **Nova ação encadeada `ACTION_OPEN_REEL_AND_MORE`:**
  - Reaproveita `openFirstReelViewer(afterOpen: AfterOpenViewer)` — a sealed class nova permite alternar o passo pós-abertura (`DumpNow` = dump directo, `TapMoreAndDump` = click no ⋮ + dump).
  - Depois do settle do viewer, procura `clips_ufi_more_button_component` (o ⋮) via `findFirstNodeAcrossWindows` e faz `performAction(ACTION_CLICK)`.
  - Aguarda `MORE_MENU_SETTLE_MS = 1000 ms` (animação do bottom sheet) e chama `dumpAllWindows("after-viewer-more")`.
  - Guarda defensiva: se o botão não estiver clicável, `findClickableAncestor` sobe até 5 níveis.
  - Se o botão ⋮ nem aparecer, `dumpAllWindows("more-not-found")` para termos evidência do que estava no ecrã.
- **UI/Notificação:** novo botão **⋮** na notificação (request 7) + "Abrir 1.º Reel + tocar Mais (+ dump)" no ecrã da app.
- **Ficheiros alterados:**
  - `IgSelectors.kt` — novo `IgSelectors.ReelViewer` completo.
  - `InstagramReaderService.kt` — sealed class `AfterOpenViewer`, refactor de `openFirstReelViewer`, novo `tapMoreInReelViewer`, constante `MORE_MENU_SETTLE_MS`, broadcast `ACTION_OPEN_REEL_AND_MORE`, notificação com botão ⋮.
  - `MainActivity.kt` — botão "Abrir 1.º Reel + tocar Mais".
  - `strings.xml` — `btn_open_reel_more`, `notif_action_open_more`.
  - `PROJECT_PROGRESS.md` — estado atual, §6.1 reorientada para completar copy-link, este log.
- **Próximo passo do utilizador:** tocar **⋮** na notificação; esperar ~4 s até o bottom sheet abrir; enviar o log entre `===== DUMP_ALL START reason=after-viewer-more =====` e `===== DUMP_ALL END =====`. Com esse dump implemento `ACTION_COPY_REEL_URL` na sessão 18.

### 2026-08-28 — Sessão 18 (Ricardo + Copilot CLI) — ⋮ do viewer descartado, Plano B (share) implementado

- **Dump `docs/screen-dumps/reel view more.txt` analisado.** O bottom sheet do ⋮ (`bottom_sheet_container`) do IG na versão actual **não tem "Copiar link"**. Apenas expõe:
  - Acções destacadas: `Guardar`, `Reproduzir`.
  - RecyclerView de feedback com `control_option_text`: "Porque é que estás a ver esta publicação", "Com interesse", "Não tenho interesse", "Denunciar".
- **Conclusão:** o ⋮ é feedback/relevância do algoritmo, não partilha. Note permanente adicionada a `IgSelectors.ReelViewer.COPY_LINK_LABELS` para evitar futuras investigações redundantes. Também adicionado `BOTTOM_SHEET_CONTAINER = "bottom_sheet_container"` para referência.
- **Plano B implementado:** o `direct_share_button` (desc="Partilhar") do strip vertical do viewer deve abrir a **sheet de partilha do IG** (grid de amigos + row de acções tipo "Copiar link", "Enviar como mensagem", "WhatsApp"…). É onde vive quase de certeza o URL.
- **Nova ação `ACTION_OPEN_REEL_AND_SHARE`:**
  - Nova entrada `AfterOpenViewer.TapShareAndDump` na sealed class.
  - `tapShareInReelViewer()` procura `direct_share_button`, faz `performAction(ACTION_CLICK)` (com fallback via `findClickableAncestor`) e agenda `dumpAllWindows("after-viewer-share")` após `SHARE_SHEET_SETTLE_MS = 1800 ms` (mais tempo que o ⋮ porque o IG carrega o grid de amigos).
  - Se o botão nem aparecer, dump automático `share-not-found`.
- **UI/Notificação:** novo botão **↗** na notificação (request 8) + "Abrir 1.º Reel + tocar Partilhar (+ dump)" no ecrã da app. Convivem os 3 botões (Abrir, ⋮, ↗) para testes/investigação.
- **Ficheiros alterados:**
  - `IgSelectors.kt` — nota permanente em `COPY_LINK_LABELS` a explicar porque não é usado no ⋮, novo `BOTTOM_SHEET_CONTAINER`.
  - `InstagramReaderService.kt` — `AfterOpenViewer.TapShareAndDump`, `tapShareInReelViewer`, constante `SHARE_SHEET_SETTLE_MS`, broadcast `ACTION_OPEN_REEL_AND_SHARE`, botão na notificação.
  - `MainActivity.kt` — botão "Abrir 1.º Reel + tocar Partilhar".
  - `strings.xml` — `btn_open_reel_share`, `notif_action_open_share`.
  - `PROJECT_PROGRESS.md` — estado, §6.1 reorientada para o share sheet, este log.
- **Próximo passo do utilizador:** tocar **↗** na notificação; aguardar ~4 s; enviar dump `after-viewer-share`. Com isso implemento `ACTION_COPY_REEL_URL` (click em "Copiar link" na share sheet → ler `ClipboardManager` → registar o URL do Reel) na sessão 19.

### 2026-08-29 — Sessão 19 (Ricardo + Copilot CLI) — `ACTION_COPY_REEL_URL` implementado

- **Dump `docs/screen-dumps/reel view more.txt` (linhas 41→108) analisado**: a share sheet do IG expõe as pills externas dentro de `direct_external_reshare_row` (RecyclerView). Cada pill é uma `ImageView` com **id genérico `com.instagram.android:id/button`** e `contentDescription` distinto:
  - "Adicionar à história"
  - "WhatsApp"
  - "Partilhar" (share nativo)
  - **"Copiar ligação"** ← ponto de extração do URL
  - "Estado do WhatsApp"
  - "SMS"
- Como o id é genérico, a identificação faz-se por `contentDescription in COPY_LINK_LABELS`.
- **Descoberta partilhada pelo utilizador:** a mesma sheet aparece via **long-press → Reencaminhar** na DM. Fica registada como via alternativa mais leve (evita carregar o Reel viewer) — pode ser usada como fallback ou como caminho preferido depois de mais testes. Não implementada nesta sessão.
- **Novo `IgSelectors.ReelViewer.SHARE_SHEET_EXTERNAL_ROW = "direct_external_reshare_row"`** (referência, não usado directamente porque procuramos por description).
- **Fluxo completo `ACTION_COPY_REEL_URL` (botão 🔗 na notificação + na app):**
  - Reaproveita 100% do PoC-7 (viewer + share).
  - Nova sealed class `AfterShare { DumpNow, ClickCopyLink }` para parametrizar o passo pós-share.
  - `clickCopyLinkInShareSheet()` faz `performAction(ACTION_CLICK)` no primeiro nó com description em `COPY_LINK_LABELS`, com `findClickableAncestor` de reserva.
  - `readReelUrlFromClipboard()` lê `ClipboardManager.primaryClip` 700 ms depois do click e loga o URL: `COPY_LINK: Reel URL = '<url>'`.
  - Fecha automaticamente a share sheet + Reel viewer com **2× `performGlobalAction(GLOBAL_ACTION_BACK)`** espaçados 400 ms → o utilizador volta à conversa sem intervenção.
- **Timings totais:** 2000 (viewer) + 1800 (share sheet) + 700 (clipboard) + 800 (2× back) ≈ 5.3 s do toque até estar de volta na conversa. Também candidato a afinação futura no tuning-pass do PoC-8.
- **Notas sobre clipboard em Android moderno:** `AccessibilityService` está autorizado a ler o clipboard mesmo com a nossa app em background; adicionalmente o click acabou de ser executado enquanto o IG (foreground) escrevia o clip, portanto a leitura no tick seguinte é fiável. Se em algum dispositivo a leitura devolver vazio, aumentar `CLIPBOARD_READ_DELAY_MS` ou intercetar o texto do toast "Ligação copiada".
- **Ficheiros alterados:**
  - `IgSelectors.kt` — `SHARE_SHEET_EXTERNAL_ROW`.
  - `InstagramReaderService.kt` — `AfterOpenViewer.TapShareAndCopyLink`, `AfterShare` sealed class, `tapShareInReelViewer(afterShare)` parametrizado, `clickCopyLinkInShareSheet`, `readReelUrlFromClipboard`, constantes `CLIPBOARD_READ_DELAY_MS`, `BACK_AFTER_COPY_DELAY_MS`, broadcast `ACTION_COPY_REEL_URL`, botão 🔗 na notificação.
  - `MainActivity.kt` — botão "Copiar URL do 1.º Reel".
  - `strings.xml` — `btn_copy_reel_url`, `notif_action_copy_url`.
  - `PROJECT_PROGRESS.md` — estado, §6.1 (validação PoC-7 + preview PoC-8), §6.2 (PoC-8 com batching), este log.
- **Próximo passo do utilizador:** tocar **🔗** na notificação numa conversa com Reel recebido → confirmar no Logcat `COPY_LINK: Reel URL = '<url>'` → colar no browser para validar. Se funcionar, **PoC-7 fecha** e arrancamos o PoC-8.

### 2026-08-29 — Sessão 20 (Ricardo + Copilot CLI) — clipboard bridge via Activity transparente

- **Log da sessão 19 mostrou:** fallback `dispatchGesture` fez o click no "Copiar ligação" passar (o utilizador confirmou visualmente e o URL ficou mesmo no clipboard do telemóvel), **mas o service ainda logou `clipboard is empty or non-text (itemCount=0)`**.
- **Causa raiz:** Android 10+ (API 29) impõe privacidade sobre o clipboard — apps que **não estão em foreground** e não são o IME default recebem sempre `null`/vazio de `ClipboardManager.primaryClip`. O AccessibilityService, mesmo tendo `BIND_ACCESSIBILITY_SERVICE`, é considerado background para este efeito. O IG conseguiu escrever (foreground), mas nós não conseguimos ler.
- **Fix:** nova `ClipboardCaptureActivity` invisível (`Theme.Translucent.NoTitleBar`, `excludeFromRecents`, `noHistory`, `singleInstance`, sem taskAffinity) que:
  - No `onCreate`: chama `getSystemService(CLIPBOARD_SERVICE)`, lê o clip, envia broadcast `ACTION_CLIPBOARD_CAPTURED` com o texto em extra `EXTRA_CLIPBOARD_TEXT` para o service, e chama `finish()` + `overridePendingTransition(0,0)` para eliminar animação.
  - Como está em foreground durante o `onCreate/onStart/onResume`, a leitura do clipboard não é bloqueada.
- **Refactor no service:**
  - `readReelUrlFromClipboard()` deixou de ler o clipboard directamente; agora só faz `startActivity(ClipboardCaptureActivity)` com flags `NEW_TASK | NO_ANIMATION | EXCLUDE_FROM_RECENTS | CLEAR_TOP`.
  - Novo `handleClipboardCaptured(intent)` recebido pelo broadcast do action-receiver: extrai o URL do extra, loga `COPY_LINK: Reel URL = '<url>'`, e só depois disso agenda os 2× `GLOBAL_ACTION_BACK` para fechar a share sheet + Reel viewer.
  - Import de `ClipboardManager` removido do service (já não usado directamente).
- **Manifest:** activity registada como `android:exported="false"` (não pode ser invocada por outras apps), sem `intent-filter` MAIN/LAUNCHER — só a nossa própria startActivity a atinge.
- **Trade-off UX:** há um flash breve (<100 ms) quando a Activity aparece invisível por cima do IG. Com `overridePendingTransition(0,0)` e `Theme.Translucent.NoTitleBar` isto é praticamente imperceptível. Alternativa mais limpa (overlay `SYSTEM_ALERT_WINDOW`) exige permissão especial pedida ao utilizador — overkill para PoC.
- **Ficheiros alterados:**
  - `AndroidManifest.xml` — registo da `ClipboardCaptureActivity`.
  - `ClipboardCaptureActivity.kt` — **novo**, ~55 linhas.
  - `InstagramReaderService.kt` — `readReelUrlFromClipboard` refactorizada, `handleClipboardCaptured` novo, wiring `ACTION_CLIPBOARD_CAPTURED` + `EXTRA_CLIPBOARD_TEXT`.
  - `PROJECT_PROGRESS.md` — este log.
- **Próximo passo do utilizador:** tocar **🔗** novamente na notificação. Devias ver agora no Logcat:
  ```
  COPY_LINK: dispatchGesture fallback accepted=true
  COPY_LINK: launched ClipboardCaptureActivity to bridge the read.
  ClipboardCapture: captureAndFinish: read clipboard -> https://...
  COPY_LINK: Reel URL = 'https://www.instagram.com/reel/…'
  ```
  Depois de confirmares o URL correcto, o PoC-7 fica **fechado** e passamos ao PoC-8.

### 2026-08-29 — Sessão 21 (Ricardo + Copilot CLI) — PoC-8 iteração 1 (Room + descoberta + feed simples)

- **Combinado com o utilizador:** o teste do clipboard bridge do PoC-7 fica adiado; validamos tudo junto com o PoC-8 na próxima sessão de testes.
- **Novo módulo `data/`** com o setup mínimo de Room:
  - `ReelEntity`: id auto, `threadTitle`, `reelAuthor`, `direction`, `kind`, `bubbleIndex`, `reelUrl` (nullable, preenchido depois pelo PoC-7 quando integrado por Reel), `discoveredAt`, `seenAt`. Index único em `reelUrl` (dedup canónico assim que tivermos URL).
  - `ReelDao`: `insert(OnConflict=IGNORE)`, `countMatching(thread, author, direction)` para dedup enquanto não temos URL, `observeAll()` como Flow ordenado por `discoveredAt DESC`, `markSeen`, `clearAll`, `count`.
  - `AppDatabase`: singleton Room 2.6.1 + KSP (já vinha nas dependências).
- **Novo `ACTION_DISCOVER_REELS`** no service (broadcast + botão 🔍 na notificação + "Descobrir Reels desta conversa" na app):
  - Reaproveita 100% `enumerateReels()` do PoC-4.
  - Snapshot dos entries para `data class Snapshot` (evita usar `AccessibilityNodeInfo` fora da thread a11y).
  - `serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)` novo, cancelado em `onDestroy`.
  - Para cada entry: `countMatching(threadTitle, reelAuthor, direction)` → se 0, insert; se >0, skip.
  - Loga `DISCOVER: thread='...' visible=N inserted=X skipped=Y totalInDb=Z`.
  - `threadTitle` vem de `lastKnownConversationTitle` (capturado no `onAccessibilityEvent` desde a sessão 8); fallback `?` se ainda não visto.
- **Novo módulo `ui/feed/`** com:
  - `FeedViewModel(AndroidViewModel)`: expõe `reels: StateFlow<List<ReelEntity>>` via `dao.observeAll().stateIn(...)` + `markSeen(id)` + `clearAll()`.
  - `FeedScreen()`: `Scaffold` + `TopAppBar` + `LazyColumn` (16 dp padding, 12 dp entre cards). Cada `ReelCard` mostra badges (RECEIVED verde / SENT roxo) + autor (`@username`) + `Partilhado em: <thread>` + timestamp `dd/MM HH:mm`. Botão "Abrir no Instagram" tenta deep-link via `Intent.ACTION_VIEW` com `setPackage("com.instagram.android")`, fallback para browser, fallback para Toast; desabilitado se `reelUrl == null` mostrando "URL ainda não capturado (usar 🔗)". Empty state amigável quando o DB está vazio.
  - `FeedActivity(ComponentActivity)`: entry point, tema dark.
- **UX flow:**
  1. Utilizador abre uma conversa no IG com Reels visíveis.
  2. Baixa shade → toca 🔍 (Descobrir) → service enumera + insere em Room. Toast? Não — só logcat. UX visível é abrir o feed a seguir.
  3. Opcionalmente toca 🔗 num Reel específico para capturar o URL (`ACTION_COPY_REEL_URL`). O URL fica no clipboard **mas ainda não é gravado por Reel** — só é logado. Integração completa (associar URL ao `ReelEntity` correcto) fica para a próxima iteração.
  4. Abre a app → "Ver feed (BD local)" → aparecem os cards.
- **Ficheiros criados/alterados:**
  - `data/AppDatabase.kt` (novo).
  - `data/ReelEntity.kt` (novo).
  - `data/ReelDao.kt` (novo).
  - `ui/feed/FeedActivity.kt` (novo).
  - `ui/feed/FeedViewModel.kt` (novo).
  - `ui/feed/FeedScreen.kt` (novo).
  - `AndroidManifest.xml` — activity `.ui.feed.FeedActivity` registada (exported=false).
  - `InstagramReaderService.kt` — imports Room/Coroutines, `serviceScope`, `discoverReels()`, `Snapshot`, `ACTION_DISCOVER_REELS`, botão 🔍 na notificação.
  - `MainActivity.kt` — botões "Descobrir" e "Ver feed".
  - `strings.xml` — strings da feed (título, empty state, badges, botões).
  - `PROJECT_PROGRESS.md`.
- **Limitações conhecidas nesta iteração:**
  - Dedup por (thread, author, direction) — colapsa múltiplos Reels do mesmo autor no mesmo thread. Vai ser substituído por dedup por URL quando integrarmos o PoC-7 por Reel.
  - Sem ExoPlayer — o feed não reproduz o vídeo. Motivo: o URL público do Reel do IG não é directamente reproduzível sem sessão autenticada; ficará resolvido no PoC-8 iteração seguinte, provavelmente com fallback "abrir no IG nativo" (que já temos).
  - Sem batching de acções ainda — as reacções/respostas continuam a acontecer 1×1 via notificação. Fila fica para a próxima iteração.
  - `ACTION_COPY_REEL_URL` ainda não escreve o URL na `ReelEntity` correspondente — só loga. Integração é o próximo passo assim que fizermos a associação Reel↔row.
- **Próximo passo do utilizador (para uma sessão de teste completa PoC-7 + PoC-8):**
  1. Puxar repo, correr no OnePlus. Aceitar permissão de notificações se necessária.
  2. Abrir uma conversa no IG com vários Reels recebidos.
  3. Baixar shade → tocar **🔗** → validar Logcat `COPY_LINK: Reel URL = '<url>'` e clipboard.
  4. Baixar shade → tocar **🔍** → Logcat `DISCOVER: thread='...' visible=N inserted=X ...`.
  5. Abrir a nossa app → tocar **"Ver feed (BD local)"** → confirmar que os cards aparecem com os dados certos (autor, thread, direction, kind, timestamp).
  6. Reportar tudo o que falhar (com Logcat).

### 2026-08-29 — Sessão 22 (Ricardo + Copilot CLI) — feedback PoC-7/8 + ajustes

- **Feedback do utilizador sobre o teste da sessão 21 (log em `docs/screen-dumps/feed.txt`):**
  1. **Clipboard bridge ainda devolve vazio no OnePlus** (`COPY_LINK: ClipboardCaptureActivity returned empty text`) — mas o URL fica mesmo no clipboard do telemóvel (o utilizador consegue colar noutras apps). O bloqueio é apenas ao **nosso** `ClipboardManager.primaryClip`. Utilizador pediu para não perder tempo aqui agora e "apenas marcar que está a acontecer".
  2. **Descoberta só pega no que está no ecrã.** Correto — é o comportamento por design nesta iteração; scroll automático das conversas fica para a próxima.
  3. **Flag `ignoreSent` respeitada** — utilizador confirma que a descoberta segue o toggle (mete SENT na BD só quando o flag está a `false`). Comportamento OK.
  4. **Não fica claro no card do feed QUEM enviou** — em DM 1-a-1 o `threadTitle` é o interlocutor, mas se o utilizador desliga a flag também aparecem os Reels que ele próprio partilhou, e "de Pedro Sardoeira" é confuso quando na verdade fui eu que enviei.
- **Ajustes desta sessão:**
  - **`discoverReels` (service):** filtro explícito por `Direction.RECEIVED` quando `isIgnoreSentEnabled()==true`, para o comportamento não depender de acidente/dedup. Log passa a incluir `visibleReceived`/`visibleSent`/`ignoreSent`/`kept`.
  - **`FeedScreen ReelCard`:** a linha sob o `@autor` deixa de dizer só "de X". Agora:
    - Se `direction=RECEIVED` → "**Recebido em <thread>**".
    - Se `direction=SENT` → "**Enviado por mim em <thread>**" a cor lilás (`0xFFCE93D8`) para saltar à vista quando misturado com recebidos.
    - Os badges "recebido"/"enviado" continuam presentes na primeira linha do card como sinal visual rápido.
  - **`ClipboardCaptureActivity`:** leitura movida de `onCreate` para `onWindowFocusChanged(hasFocus=true)`, o único momento em que temos garantidamente window focus (e portanto foreground para efeitos de clipboard). Adicionado retry curto (3× 80 ms) para cobrir o caso em que o clip ainda não chegou. Se mesmo assim vier vazio, o log mostra `tryCaptureWithRetry: gave up`. **Não sabemos ainda se resolve** — utilizador vai validar na próxima sessão.
- **Registado explicitamente em §5:**
  - Descoberta só apanha os Reels visíveis no RecyclerView do momento (scroll automático fica para próxima iteração — o service precisa de fazer swipes verticais dentro do `message_list` e chamar `enumerateReels` a cada scroll até esgotar histórico).
  - Identificação do membro que partilhou em grupos: ainda inviável a partir da conversa. **Via alternativa confirmada:** o Reel viewer (`sender_username_or_fullname`) expõe o nome humano — na próxima iteração podemos capturar este campo dentro do fluxo do PoC-7 e persistir em `ReelEntity.dmSender` (coluna nova).
- **Limitações reforçadas em §5 (batching, foreground, agora + estas):**
  - Sem batching de acções ainda — próxima iteração.
  - Leitura do clipboard depende do foreground focus da Activity bridge (não confirmado funcionar em 100% dos dispositivos).
- **Ficheiros alterados:**
  - `InstagramReaderService.kt` — `discoverReels` filtra + log detalhado.
  - `ClipboardCaptureActivity.kt` — leitura em `onWindowFocusChanged` com retry.
  - `FeedScreen.kt` — texto do card por direction.
  - `strings.xml` — `feed_received_in`, `feed_sent_in`.
  - `PROJECT_PROGRESS.md`.
- **Próximo passo do utilizador:** re-testar rapidamente (🔗 + 🔍 + Ver feed). Confirmar que:
  - Cards de recebidos dizem "Recebido em <thread>" (normal) e enviados "Enviado por mim em <thread>" (lilás).
  - Log da descoberta tem o breakdown `visibleReceived=X visibleSent=Y kept=Z inserted=N skipped=M`.
  - Se o clipboard bridge passou a devolver o URL. Se não, ficamos com essa via para retomar depois.

### 2026-08-29 — Sessão 23 (Ricardo + Copilot CLI) — PoC-7 ✅ end-to-end + integração PoC-7↔PoC-8

- **Teste do utilizador (`docs/screen-dumps/feed.txt`):** 🎉
  - `COPY_LINK: Reel URL = 'https://www.instagram.com/reel/Dcl1CZDvJFE/?igsi=djloczVrOXl5dmFo'` — o fix da sessão 22 (`onWindowFocusChanged` + retry na `ClipboardCaptureActivity`) resolveu o problema de leitura em Android 10+. **PoC-7 ✅ fechado end-to-end.**
  - Novos logs `DISCOVER: thread='...' visibleReceived=X visibleSent=Y ignoreSent=Z kept=K inserted=N skipped=M totalInDb=T` tornaram o comportamento super claro: com `ignoreSent=true` só grava recebidos (kept=visibleReceived), com `false` grava tudo (kept=visibleReceived+visibleSent). Múltiplos scrolls exibem dedup a funcionar (skipped>0 quando revisita).
- **Integração PoC-7 ↔ PoC-8:** o toque em 🔗 passa a persistir uma linha completa em Room. Novo comportamento:
  - **Antes de dispatchar o tap** no bubble, o service guarda `pendingCopy: PendingCopy(threadTitle, direction, reelAuthor, kind, bubbleIndex)` — snapshot do target antes de abrir o viewer.
  - **Dentro de `tapShareInReelViewer`** (antes de tocar Partilhar), chama `enrichPendingCopyFromViewer()` que lê `sender_username_or_fullname` da árvore actual do viewer. Este campo expõe o **nome humano** de quem partilhou o Reel na DM — em 1-a-1 é o interlocutor, em grupo é o membro específico (resolve o problema §5 sem pixel-hash).
  - **No `handleClipboardCaptured`**: se o URL veio populado, chama `persistCopiedReel(pending, url)`:
    - Tenta `dao.insert(row)` com `reelUrl` unique index — se o URL é novo, insere linha completa (threadTitle + reelAuthor + dmSender + direction + URL).
    - Se o URL já existe (unique conflict, `insert` devolve -1), faz `dao.updateDmSenderByUrl(url, dmSender)` para backfillar apenas o dmSender quando o registo antigo tinha null.
  - **BACK gestures** movem-se para depois da persistência (mesmo delay como antes; a persistência corre no `Dispatchers.IO`).
- **Schema Room v2:**
  - `ReelEntity.dmSender: String?` (nova coluna, nullable).
  - `AppDatabase` bump de `version=1` para `version=2` com `.fallbackToDestructiveMigration()` — no PoC os dados são regenerados por um `Descobrir` do utilizador.
  - `ReelDao.updateDmSenderByUrl(url, dmSender): Int` para backfill.
- **UI do feed:** nova composable `WhoAndWhereLine(reel)` substitui o texto anterior por lógica ciente do `dmSender`:
  - `SENT`: "Enviado por mim em <thread>" (lilás).
  - `RECEIVED` + `dmSender` nulo: "Recebido em <thread>" (comportamento anterior).
  - `RECEIVED` + `dmSender == thread` (típico 1-a-1): "Recebido de <sender>" (mais natural).
  - `RECEIVED` + `dmSender != thread` (grupo): "Recebido de <sender> em <thread>" — **agora sabe-se quem partilhou** mesmo em grupos.
- **Discovery rápida** deixa `dmSender = null` explicitamente (só o fluxo 🔗 preenche). O feed lida com isso naturalmente.
- **Ficheiros alterados:**
  - `data/ReelEntity.kt` — nova coluna `dmSender`, kdoc actualizada.
  - `data/AppDatabase.kt` — bump v2 + destructive migration.
  - `data/ReelDao.kt` — `updateDmSenderByUrl`.
  - `service/InstagramReaderService.kt` — `PendingCopy` + `pendingCopy` + `enrichPendingCopyFromViewer` + `persistCopiedReel` + refactor de `handleClipboardCaptured` e `openFirstReelViewer` para popular `pendingCopy` quando `AfterOpenViewer.TapShareAndCopyLink`.
  - `ui/feed/FeedScreen.kt` — `WhoAndWhereLine` composable.
  - `strings.xml` — `feed_received_from`, `feed_received_from_in`.
- **Fluxo de teste recomendado (na próxima sessão do utilizador):**
  1. Puxar repo. **Nota:** a BD local vai ser recriada por `fallbackToDestructiveMigration` — perdes as rows criadas em testes anteriores. Não é grave.
  2. Abrir uma conversa com um Reel recebido → tocar **🔗** → confirmar Logcat com:
     - `COPY_LINK: pendingCopy=PendingCopy(threadTitle=..., direction=RECEIVED, ...)`
     - `COPY_LINK: enriched pendingCopy with dmSender='Pedro Sardoeira' from viewer.`
     - `COPY_LINK: Reel URL = 'https://...'`
     - `COPY_LINK: inserted row id=... dmSender=Pedro Sardoeira totalInDb=1.`
  3. Abrir a app → "Ver feed" → o card do Reel copiado deve mostrar "Recebido de Pedro Sardoeira" (em 1-a-1) + botão "Abrir no Instagram" activo (com URL).
  4. Repetir num **grupo**: deve ler o nome do membro específico (ex. "João Vieira") e mostrar "Recebido de João Vieira em O Burro a Vaca e os Reis Magos".
  5. Toque **🔍** deve continuar a descobrir batch (sem URL) e as rows aparecem sem "Abrir no IG" activo (fica "URL ainda não capturado (usar 🔗)").
- **A seguir (PoC-8 iteração 3):** batching de acções (fila `PendingActionEntity` para reagir/responder de forma diferida), scroll automático dentro da conversa para descobrir histórico completo, e eventual player (provavelmente WebView com o URL do IG, pois o video URL não é directamente reproduzível).

### 2026-08-29 — Sessão 24 (Ricardo + Copilot CLI) — limpeza + clarificação

- **Diagnóstico da causa do log estranho da tentativa anterior:** o utilizador tinha um commit local `4750b8f "feed errors"` que **reverteu** o meu commit `50b02d1` da sessão 23. Testou nessa versão revertida (por isso não apareciam os logs `pendingCopy=`, `enriched pendingCopy`, `inserted row`). Depois fez merge com o remoto, que reintroduziu o código — mas os testes que reportou foram na versão sem a integração. Preciso confirmar isto com o utilizador antes de assumir que a integração está partida no HEAD actual.
- **Limpeza pedida pelo utilizador — remoção de botões exploratórios:**
  - Removidos da UI (app + notificação):
    - `Long-press no 1.º Reel` (`ACTION_LONG_PRESS_FIRST_REEL`).
    - `Dump de todas as janelas` (`ACTION_DUMP_ALL_WINDOWS`).
    - `Listar Reels na conversa` (`ACTION_LIST_REELS`).
    - `Abrir 1.º Reel no viewer + dump` (`ACTION_OPEN_REEL`).
    - `Abrir 1.º Reel + tocar Mais` (`ACTION_OPEN_REEL_AND_MORE`).
    - `Abrir 1.º Reel + tocar Partilhar` (`ACTION_OPEN_REEL_AND_SHARE`).
    - `ACTION_DUMP_TREE` (nunca teve botão, só via adb).
  - **Simplificação do código:** removidos sealed classes `AfterOpenViewer` e `AfterShare` (só ficava um caminho útil), função `tapMoreInReelViewer`, função `listReels`, constante `MORE_MENU_SETTLE_MS`. `openFirstReelViewer` agora só dispatched o tap e chama `tapShareInReelViewer()` — sem parâmetros. O log `afterOpen=` desaparece (não faz mais sentido).
  - **Superfície pública mantida (5 acções):** `ACTION_REACT_HEART`, `ACTION_REACT_LAUGH`, `ACTION_REPLY_FIRST_REEL_MOCK`, `ACTION_COPY_REEL_URL`, `ACTION_DISCOVER_REELS` (+ `ACTION_CLIPBOARD_CAPTURED` interno).
  - **Notificação:** 5 botões (❤, 😂, 👀, 🔗, 🔍). Estava com 10.
  - **`MainActivity`:** botões reduzidos aos 5 correspondentes + "Ver feed (BD local)" + o switch de `ignoreSent` + os 2 botões iniciais (activar acessibilidade / abrir Instagram).
- **`BUILD_TAG = "build=s24"`** adicionado ao log de `Action receiver registered` para o utilizador conseguir confirmar visualmente que build está a correr. Se numa próxima falha o log não mostrar `build=s24`, é sinal claro de que o APK ainda tem uma versão antiga.
- **Header do service (kdoc) reescrita** a descrever a superfície actual (5 acções PoC-4/5/6/7/8) em vez do histórico exploratório.
- **Ficheiros alterados:**
  - `service/InstagramReaderService.kt` — grande limpeza (~130 linhas removidas).
  - `MainActivity.kt` — 6 botões e 6 parâmetros do `HomeScreen` removidos.
  - `strings.xml` — 8 strings removidas.
  - `PROJECT_PROGRESS.md`.
- **Ficheiros mantidos intactos** (não foram tocados nesta sessão): `data/*`, `ClipboardCaptureActivity.kt`, `ui/feed/*` — a integração PoC-7↔PoC-8 da sessão 23 fica.
- **Próximo passo do utilizador:**
  1. Puxar o repo. Verificar que HEAD é o commit desta sessão.
  2. Rebuild + run no OnePlus.
  3. Ao arrancar, no Logcat verificar a linha `Action receiver registered (build=s24 ...)` — confirma que a versão certa está a correr.
  4. Testar os pontos 1 (DM) e 2 (grupo) da checklist da sessão anterior — agora **devem** funcionar porque o código da integração está no HEAD e a UI só tem os botões que servem.

### 2026-08-29 — Sessão 25 (Ricardo + Copilot CLI) — PoC-8 iter 2 ✅ validada + fix promoção 🔍→🔗

- **Resultado dos testes A/B/C/D pedidos na sessão 24** (log em `docs/screen-dumps/feed.txt`, `build=s24`):
  - **A** ✅ dedup 🔗 2× — `COPY_LINK: URL already in DB — backfilled dmSender rows=0`.
  - **B** ✅ "Abrir no Instagram" no feed → abre o IG nativo.
  - **C** ❌ interacção 🔍 → 🔗: uma nova row é inserida em vez de promover a existente (URL null). Confirmado no comportamento observado.
  - **D** ✅ toggle "Ignorar Reels enviados por mim" — texto lilás "Enviado por mim em X" aparece quando desactivado.
- **Fix do C nesta sessão:** três-way dedup no `persistCopiedReel`:
  1. **Promote** — nova query DAO `promoteDiscoveryRow(url, dmSender, thread, author, direction)` que faz `UPDATE reels SET reelUrl=?, dmSender=? WHERE reelUrl IS NULL AND thread=? AND (author matches) AND direction=?`. Se `promoted > 0`, done.
  2. **Insert** — senão, `dao.insert(row)`; se `id > 0`, done.
  3. **Backfill dmSender por URL** — senão (URL colidiu no unique index), `updateDmSenderByUrl` — mesmo comportamento de antes.
- **Log:** cada caminho tem a sua linha clara — `promoted N discovery-only row(s)`, `inserted row id=...`, `URL already in DB — backfilled dmSender rows=...`. Facilita diagnóstico.
- **`BUILD_TAG` bumped para `build=s25`** — utilizador verifica no log inicial que está a correr esta versão.
- **Sem bump da DB** — só uma nova query DAO, sem alterar schema. `dmSender` já existe desde v2.
- **Ficheiros alterados:**
  - `data/ReelDao.kt` — nova query `promoteDiscoveryRow`.
  - `service/InstagramReaderService.kt` — `persistCopiedReel` reescrito com 3 caminhos, `BUILD_TAG` bumped.
  - `PROJECT_PROGRESS.md` — estado actual (PoC-8 iter 2 ✅), §6 reorganizada com plano para iter 3 (batching + scroll auto + player), este log, e "quick start" actualizado com nova estrutura de ficheiros.
- **PoC-8 iter 2 fica fechado.** Próximo passo é a iter 3: batching de acções (fila persistente + executor sequencial + botão "Aplicar N no Instagram"), scroll automático da conversa (`ACTION_DISCOVER_REELS_HISTORY`), player rudimentar (WebView provavelmente).
- **Próximo passo do utilizador** (opcional, só para consolidar):
  - Puxar → confirmar `build=s25`.
  - Repetir o teste C — 🔍 numa conversa nova, depois 🔗 no mesmo Reel. Esperado: `COPY_LINK: promoted 1 discovery-only row(s) to enriched ...` e no feed **1 card apenas** para esse Reel (não 2).
- **Validação final (fim da sessão 25):** utilizador confirmou `build=s25` a correr no OnePlus e o novo comportamento a aparecer nos logs (mensagens `promoted` / `URL already in DB` no fluxo do 🔗). **PoC-8 iter 2 totalmente fechado.** Próxima sessão arranca do commit `aaff892` com o plano da §6.1 (iter 3: batching + scroll auto + player).

### 2026-08-29 — Sessão 26 (Ricardo + Copilot CLI) — PoC-8 iter 3 parte A: batching de acções

- **Objetivo desta sessão:** implementar a *parte A* da iter 3 (batching de acções). Scroll automático e player WebView ficam para sessões seguintes — a parte A é a que mais muda a UX percebida e o utilizador queria isso primeiro.
- **Design escolhido (documentado no código, secção "PoC-8 iteration 3 — batching executor" em `InstagramReaderService.kt`):**
  - Nova tabela `pending_actions` com FK ON DELETE CASCADE para `reels`. Rows têm `id, reelId, kind (REACT_HEART|REACT_LAUGH|REPLY_TEXT), payload, status (PENDING|RUNNING|DONE|FAILED), createdAt, executedAt, error`.
  - Feed enfileira em vez de executar (novo bottom bar: **"Aplicar N acções no Instagram"**). Cada card ganhou 3 botões pequenos (❤ / 😂 / 👀) que inserem `PENDING`. Reacções são deduplicadas por `(reelId, kind)` enquanto ainda estiverem `PENDING`; réplicas de reply são permitidas.
  - Novo broadcast `ACTION_APPLY_PENDING` — service traz IG à frente (`runInInstagram`), lê a fila FIFO em IO, corre `runBatchStep` no main handler para cada acção (delay 2500ms entre acções para o IG fechar o popup anterior). A notificação persistente durante o batch mostra `A aplicar N/M…` com progress bar.
  - Botão ▶ adicionado à notificação persistente (`R.string.notif_action_apply`, `requestCode = 11`) para se poder disparar `ACTION_APPLY_PENDING` sem tirar o IG da frente.
- **Limitação assumida (por design da iter 3 parte A):**
  - **Nesta iteração NÃO há navegação entre conversas.** Cada `PendingAction` é comparado contra o `lastKnownConversationTitle` (header do IG). Rows para outras conversas são marcados `FAILED` com o motivo (`Conversa activa é 'X' mas a acção pertence a 'Y'`), e o executor avança rápido pelos que não conseguem executar. Fluxo do utilizador: enfileira à vontade → antes de aplicar, abrir a conversa relevante no IG → tocar ▶ → volta ao feed, muda de conversa, repete.
  - Navegação real por thread precisa do `thread_id` estável (PoC-9 — anotado em §6.2).
- **DB schema evolution — v3:**
  - Adiciona `pending_actions` (nova tabela).
  - Continuamos com `fallbackToDestructiveMigration()` — utilizador perde os Reels descobertos (regenerar com uma passagem de 🔍).
- **Ficheiros novos / alterados:**
  - **NOVOS:** `data/PendingActionEntity.kt`, `data/PendingActionDao.kt`.
  - `data/AppDatabase.kt` — v2 → v3, adiciona `pendingActionDao()`.
  - `data/ReelDao.kt` — adiciona `byId(id)` (necessário para o executor resolver o Reel do PendingAction).
  - `service/InstagramReaderService.kt` — novo `ACTION_APPLY_PENDING`, novo receiver, executor `applyPendingActions()`, `runBatchStep()`, `updateProgressNotification()`, botão ▶ na notificação. `BUILD_TAG = "build=s26"`.
  - `ui/feed/FeedViewModel.kt` — expõe `pendingCount`, `pendingByReelKind`, e as funções `enqueueReaction`/`enqueueReply`/`clearTerminal`.
  - `ui/feed/FeedScreen.kt` — 3 botões por card (`Enfileirar ❤/😂/👀`) com badges laranja `❤ na fila` quando já existe pendente; bottom bar `Aplicar N acções no Instagram` (ou `Sem acções pendentes` quando fila vazia) + link `Limpar histórico de ações concluídas`.
  - `res/values/strings.xml` — 12 strings novas (feed_queue_*, feed_pending_badge_*, feed_queued_toast, feed_apply_*, notif_action_apply, notif_apply_progress).
- **Comportamento esperado no device:**
  1. Utilizador abre uma conversa no IG → toca 🔍 na notificação → cards aparecem no feed.
  2. No feed, toca `Enfileirar ❤` num dos cards → toast "Ação enfileirada" + badge laranja "❤ na fila" no card + bottom bar passa a `Aplicar 1 acções no Instagram`.
  3. Volta ao IG e certifica-se que a conversa correcta está aberta.
  4. Puxa a notif e toca ▶ (ou vai à app e toca `Aplicar N…`). No Logcat vê `APPLY_PENDING: starting drain (currentThread='X')` seguido de `APPLY_PENDING: step 1/N ...` para cada acção. A notif mostra `A aplicar N/M…` com progress bar. Cada reacção é o mesmo primitivo do PoC-5.
  5. Se a conversa activa não bate certo, `APPLY_PENDING: step X/N skipped — Conversa activa é 'A' mas a acção pertence a 'B'` e a row fica `FAILED`.
- **Testes propostos ao utilizador (na próxima sessão dele):**
  - **A — enfileirar + aplicar 1 acção (mesma conversa):** abre conversa, 🔍, no feed toca `Enfileirar ❤` num card, volta ao IG (mesma conversa), toca ▶. Esperado no Logcat: `APPLY_PENDING: step 1/1 actionId=... kind=REACT_HEART thread='...'` seguido do fluxo normal de `LONG_PRESS` + `REACT`.
  - **B — batching 3 acções (mesma conversa):** enfileira `❤`, `😂` e `👀` no mesmo card. Aplica → `step 1/3, 2/3, 3/3`, cada uma com 2500ms de intervalo. Cada emoji cai no primeiro Reel visível (limitação partilhada com PoC-5/6). Confirma no IG que as reacções ficaram.
  - **C — wrong-thread FAIL:** enfileira no feed uma acção para um Reel do grupo X, mas quando aplicares fica na conversa Y. Esperado: `APPLY_PENDING: step 1/1 skipped — Conversa activa é 'Y' mas a acção pertence a 'X'` e a row aparece com estado `FAILED` (não é executada; para já não a mostramos na UI, só desaparece do count `PENDING`). Pode confirmar no feed que o botão `Enfileirar ❤` desse card volta a estar activo.
  - **D — dedup:** toca 2× `Enfileirar ❤` no mesmo card. Segundo toque → toast "Já está enfileirada", contagem fica em 1.
  - **E — reply:** enfileira `Enfileirar 👀`, aplica. Esperado: o fluxo do PoC-6 corre, `REPLY:` logs, mensagem "👀" enviada no IG.
- **Coisas que sabemos que ainda não são perfeitas (aceitáveis para PoC iter 3-A):**
  - Executor marca `DONE` **optimisticamente** depois de dispatch do primitivo — não sabemos se o IG realmente aplicou. Callback do `dispatchGesture` só nos diz que o gesto saiu, não que teve efeito. Para PoC OK: utilizador confirma visualmente.
  - "Primeiro Reel recebido visível" é o alvo tanto do PoC-5/6 como do executor. Se enfileirares 2× `❤` para 2 Reels diferentes visíveis, as duas reacções vão para o mesmo (o primeiro). Precisamos de resolver isto na iter 3-B ou iter 4 — provavelmente identificando o Reel pelo `reelUrl` na fila e fazendo scroll até ele estar visível.
  - Não temos ainda UI para ver as rows `FAILED` / `DONE`. Botão "Limpar histórico de ações concluídas" limpa-os todos. Pode ser útil expor um sub-ecrã "Histórico da fila" mais tarde.
- **Próximos passos (na ordem sugerida):**
  1. Utilizador valida os cenários A→E acima.
  2. **iter 3-B (scroll auto):** `ACTION_DISCOVER_REELS_HISTORY` — swipe vertical dentro dos bounds da `message_list`, `enumerateReels` a cada scroll, parar quando não há novos entries em N scrolls seguidos.
  3. **iter 3-C (player WebView):** substituir o botão "Abrir no Instagram" por um player embutido — WebView carrega `reel.reelUrl`; se der problemas de layout/autoplay, cair de volta para o botão actual.
  4. **iter 4 (endereçar Reels específicos no executor):** alterar `runBatchStep` para procurar dentro da `message_list` o bubble com o `reelAuthor` + heurística correspondente, em vez de bater sempre no 1.º recebido. Provavelmente vai obrigar a scrollar até encontrar o Reel — encaixa bem depois da iter 3-B.

### 2026-08-29 — Sessão 27 (Ricardo + Copilot CLI) — fixes s26: notificação + dedup 👀 + race condition

- **Testes A e B da sessão 26 passaram** (log em `docs/screen-dumps/Enfileirar.txt`, `build=s26`):
  - **A** ✅ 1× ❤ enfileirado → `APPLY_PENDING: step 1/1 kind=REACT_HEART` → `REACT: performAction(ACTION_CLICK) on emoji '❤' returned true`. Perfeito.
  - **B** parcial ✅: 5 acções enfileiradas (2 hearts, 1 laugh, 2 replies acidentais). Steps 1–3 correram bem. Step 4 (REPLY_TEXT) disparou o long-press **antes** do step 3 acabar de clicar em enviar — `LONG_PRESS: target index=0 ... afterLongPress=ReplyWithText` na linha 53 e `REPLY: send button click returned true` do step 3 só na linha 64 — race condition clara. Step 4 falhou o `REPLY: 'Responder' item not found` (linha 66) e o executor rebentou para o step 5.
- **Problema #1 — 👀 pode ser enfileirado infinitamente + sem cancelar:** utilizador acumulou 2× 👀 sem se aperceber. Botão nunca ficava disabled (por design meu na s26 — "replies allowed to stack") e nada indicava quantos já estavam enfileirados. E depois de enfileirar não havia forma de anular.
- **Problema #2 — notificação persistente na s26 tinha 6 botões (❤ 😂 👀 🔗 🔍 ▶):** no OnePlus/OxygenOS, o layout colapsado só mostra 3 botões e "os primeiros 3" são ❤ 😂 👀 — todos os do batching (🔗 🔍 ▶) ficavam invisíveis. Utilizador confirmou "só tem coração, risos e olhos, e clicar em qualquer sítio clica no rectângulo todo — não há botões pequenos individuais". Isto tornou o novo workflow do batching (que dependia do ▶) impossível de arrancar sem entrar na app.
- **Problema #3 — race condition entre steps 👀+seguinte:** replies demoram ~3500ms (long-press 600 + settle 1500 + composer 900 + send 500), mas `BATCH_STEP_INTERVAL_MS` era 2500ms — o próximo step arrancava antes de o anterior fechar a UI.
- **Fixes aplicados nesta sessão:**
  1. **Dedup do 👀 no feed** (`FeedViewModel.enqueueReply`) — mesma regra que ❤ e 😂: `countPending(reelId, KIND_REPLY_TEXT) > 0` bloqueia. Se o utilizador tocar 2×, o segundo toast é "Já está enfileirada". Botão fica disabled visualmente. Consequência: um Reel só pode ter uma resposta 👀 pendente ao mesmo tempo (para PoC MVP: suficiente, e evita spam acidental). Se mais tarde quisermos suportar réplicas repetidas ou texto custom, a decisão de UI fica na iter 4.
  2. **Botão "✕ Cancelar acções pendentes deste Reel"** aparece por baixo dos 3 botões de enfileirar **só quando o card tem pelo menos uma acção `PENDING`**. Toca → nova query DAO `cancelPendingForReel(reelId)` faz `DELETE FROM pending_actions WHERE reelId = ? AND status = 'PENDING'`. Rows `RUNNING`/`DONE`/`FAILED` são preservados (não queremos cancelar algo já a meio no executor).
  3. **Notificação persistente reduzida a 3 botões:** **🔍 Descobrir**, **🔗 Copiar URL**, **▶ Aplicar fila**. As direct reactions/reply (❤ 😂 👀) saíram do shade — o utilizador tem-nas ainda no ecrã da app se precisar de testes ad-hoc. Adicionado `setContentIntent(pendingActivity(FeedActivity))` para que tocar no corpo da notificação abra o feed. Texto actualizado para `🔍 descobre · 🔗 copia URL · ▶ aplica fila. Toca para abrir o feed.`.
  4. **Delays de batching por-kind:** `BATCH_STEP_INTERVAL_REACTION_MS = 2500` (unchanged) mas `BATCH_STEP_INTERVAL_REPLY_MS = 4500` (novo). Após um step `REPLY_TEXT` o executor espera 4500ms antes do seguinte. Fast-skip para wrong-thread/unknown-kind é agora `BATCH_STEP_FAST_SKIP_MS = 400`.
- **`BUILD_TAG` bumped para `build=s27`.** Sem alteração de schema — a tabela `pending_actions` v3 fica.
- **Ficheiros alterados:**
  - `data/PendingActionDao.kt` — nova query `cancelPendingForReel(reelId)`.
  - `ui/feed/FeedViewModel.kt` — `enqueueReply` passa a dedup; nova função `cancelPendingForReel(reelId)`.
  - `ui/feed/FeedScreen.kt` — botão `Enfileirar 👀` respeita dedup; novo TextButton `✕ Cancelar acções pendentes deste Reel` no card quando há PENDING.
  - `service/InstagramReaderService.kt` — notificação com 3 botões + `setContentIntent(FeedActivity)`; `pendingActivity(cls, requestCode)` helper novo; delays de batching por-kind; `BUILD_TAG` bumped.
  - `res/values/strings.xml` — `notif_text` novo, `feed_cancel_pending` novo. Strings `notif_action_heart/laugh/reply` continuam declaradas (usam-nas o histórico de progress e podemos vir a precisar delas no futuro — no code actual não são referenciadas).
  - `PROJECT_PROGRESS.md` — este log.
- **Testes propostos ao utilizador (próxima sessão dele):**
  - **A2 — notificação com botões visíveis:** puxa a notification shade. Confirma que aparecem 3 botões distintos e tocáveis: **🔍**, **🔗**, **▶**. Toca em cada um separadamente e verifica no Logcat: `IGReaderService … DISCOVER: …` / `COPY_LINK: …` / `APPLY_PENDING: starting drain …`.
  - **A3 — corpo da notificação abre o feed:** toca no meio da notificação (não nos botões). Deve abrir directamente a activity `Feed`. Se em vez disso abrir a MainActivity, é sinal de que o `contentIntent` não pegou.
  - **B2 — batching de 5 acções sem race:** limpa histórico (bottom bar → **Limpar histórico de ações concluídas**). Enfileira `Enfileirar ❤`, `Enfileirar 😂`, `Enfileirar 👀`. Volta ao IG e toca **▶**. Deves ver os 3 steps sem `REPLY: 'Responder' item not found`. Confirmar no IG que o Reel ficou com ❤ (ou 😂 — o IG mantém só uma reacção por utilizador; ambos vão para o mesmo Reel) e que uma mensagem `👀` foi enviada.
  - **D2 — dedup 👀:** no feed, toca `Enfileirar 👀` uma vez → toast "Ação enfileirada", botão fica cinzento. Toca outra vez (se conseguires clicar mesmo estando disabled) — se o botão for realmente honrado como disabled, o toast NÃO aparece. Confirma que a bottom bar continua a `Aplicar 1 acções…` (não sobe para 2).
  - **F — cancelar pendentes:** enfileira `❤`, `😂`, `👀` num card. Aparece o **✕ Cancelar acções pendentes deste Reel**. Toca. Todos os badges laranja desaparecem, os 3 botões voltam a estar activos, bottom bar volta a `Sem acções pendentes`. Não passa nada no IG.
- **Limitações conhecidas que ainda ficam para futuras iterações (só para o utilizador saber):**
  - Executor continua a bater sempre no **1.º Reel recebido visível** (limitação partilhada com PoC-5/6). Quando enfileirares acções para 2 Reels diferentes visíveis, os dois acabam por atacar o mesmo bubble. Ainda por resolver — o plano é a iter 4 (targeting por `reelAuthor` ou `reelUrl` na fila).
  - O executor marca `DONE` optimisticamente depois de dispatch — o Logcat pode dizer `DONE` mesmo que o IG não aceite a reacção. Confirma sempre visualmente.
  - Não há ainda uma UI para ver as rows `FAILED` do wrong-thread (só o Logcat conta a história). O botão `Limpar histórico de ações concluídas` funciona também para `FAILED` — mesmo comportamento que a s26.

### 2026-08-29 — Sessão 28 (Ricardo + Copilot CLI) — PoC-8 iter 3 partes B + C

- **Confirmação da parte A (s26 + s27):** utilizador validou em device que "dos testes que mencionaste funciona tudo" — A2 notificação com 3 botões distintos, A3 corpo abre feed, B2 batching sem race, D2 dedup 👀, F cancelar pendentes. Tudo OK.
- **Quirk anotado (cosmético, não bloqueia):** a ordem dos badges no topo do card não segue a ordem de enfileirar. Se enfileirares primeiro `Enfileirar 😂` e depois `Enfileirar ❤`, o card mostra `❤ na fila` antes de `😂 na fila` (ordem fixa alfabética / posição das checks no código). Não é problema funcional — o executor continua a respeitar `createdAt ASC`. Fica como TODO cosmético (ver §6.1). Fix é ordenar por `createdAt` numa próxima iteração.
- **Parte B (scroll auto) implementada:**
  - Novo `ACTION_DISCOVER_REELS_HISTORY` no service. Registado no receiver.
  - `discoverReelsHistory()` orquestra o ping-pong entre `doHistoryEnumerate` e `doHistoryScroll`. Estado agregado em `HistoryState(threadTitle, ignoreSent, totalScrolls, totalInserted, totalSkipped, consecutiveEmpty)`.
  - Estratégia de scroll: primeiro tenta `messageList.performAction(ACTION_SCROLL_BACKWARD)` — respeita o fling/deceleration do IG. Se falhar, fallback para `dispatchGesture` de swipe DOWN dentro dos bounds da `message_list` (finger de y≈25% para y≈85% do bounds, duração 500ms).
  - Enumeração usa exactamente o mesmo `enumerateReels` e a mesma dedup (`countMatching` por thread+author+direction) que o `discoverReels()` rápido — reaproveitamento total.
  - Stop conditions: (a) `consecutiveEmpty >= 3` (heurística para "chegámos ao topo"), (b) `totalScrolls >= 30` (cap seguro), (c) IG deixou de ser foreground.
  - Guarda `historyInProgress = true` durante o run. Segunda invocação enquanto está a correr é ignorada com log `HISTORY: already in progress, ignoring.`.
  - Progresso: nova `updateHistoryProgressNotification(state)` — a notificação persistente mostra `A descobrir histórico… scroll X/30, Y novos Reels` com progress bar (max = 30). No fim, `postControlNotification()` restaura a UI normal de 3 botões.
  - **UI:** novo botão no `MainActivity` — **"Descobrir histórico (scroll auto)"**. Não fica na notificação porque o Android colapsaria para 3 (ver s27) — mais um botão empurrava o ▶ para fora.
- **Parte C (WebView player) implementada:**
  - Nova activity `ui/player/ReelPlayerActivity` (Compose + `AndroidView { WebView }`). Extras: `EXTRA_URL`.
  - WebView com `javaScriptEnabled=true`, `domStorageEnabled=true`, `mediaPlaybackRequiresUserGesture=false`, `useWideViewPort=true`, `loadWithOverviewMode=true`. `WebChromeClient` + `WebViewClient` default.
  - **Feed reworkado:**
    - Botão primary muda de "Abrir no Instagram" para **"▶ Ver Reel aqui"** (abre `ReelPlayerActivity`).
    - Novo link secundário **"↗ Abrir no Instagram nativo"** por baixo, que preserva o comportamento antigo (intent para `com.instagram.android`). É o safety net caso a WebView falhe (login wall, geoblock, etc.).
  - Registada no `AndroidManifest.xml` com `configChanges="orientation|screenSize|keyboardHidden|screenLayout|smallestScreenSize"` para o WebView sobreviver a rotação sem reload.
  - Permission `INTERNET` já estava declarada (usada pelo IG intent-view). WebView herda-a.
- **`BUILD_TAG` bumped para `build=s28`.** Sem alteração de schema Room (parte B só insere na tabela `reels` existente; parte C não toca em BD).
- **Ficheiros alterados:**
  - `service/InstagramReaderService.kt` — nova `ACTION_DISCOVER_REELS_HISTORY` + implementação completa (~250 linhas). `BUILD_TAG` bumped. Log line do receiver actualizada com `history=`.
  - `MainActivity.kt` — novo botão `btn_discover_reels_history`.
  - **NOVOS:** `ui/player/ReelPlayerActivity.kt` (WebView + Compose scaffold).
  - `ui/feed/FeedScreen.kt` — `ReelCard` recebe agora `onPlayInApp` e `onOpenInInstagram` separados. Botão primary + secondary link.
  - `AndroidManifest.xml` — declaração da nova activity.
  - `res/values/strings.xml` — `btn_discover_reels_history`, `notif_history_progress`, `player_title`, `player_missing_url`, `feed_play_in_app`, `feed_open_in_ig_native`.
  - `PROJECT_PROGRESS.md` — este log + estado actual + §6.1 reescrita para a próxima sessão focar na validação + PoC-9.
- **Testes propostos ao utilizador** (na §6.1 acima — resumo aqui):
  - **B1** — history scroll em conversa com Reels antigos. Espera-se `HISTORY: scroll N/30` sequencial, com contadores no logcat.
  - **B2** — history scroll numa conversa com histórico curto. Espera-se `HISTORY: stopping — 3 consecutive empty scrolls.`.
  - **B3** — history scroll interrompido (meter outra app à frente). Espera-se `HISTORY: IG no longer foreground during scroll, stopping.`.
  - **C1** — tocar **"▶ Ver Reel aqui"** num card com URL. Confirmar que a WebView carrega o Reel. Se não carrega, é um fail conhecido — o fallback nativo cobre.
  - **C2** — tocar **"↗ Abrir no Instagram nativo"** — deve abrir o IG nativo (comportamento antigo garantido).
- **Coisas para o utilizador saber:**
  - History scroll pode demorar até ~25s (30 scrolls × 0.8s settle) no pior caso. Não é rápido. Se conversa for enorme e o cap de 30 for atingido, corre outra vez para continuar.
  - Se a WebView C1 mostrar login wall ou "Open in app", o teste C está feito com o resultado real — reportamos e caímos no B (thumbnail + botão nativo) na próxima iteração.
  - Executor de batching (ACTION_APPLY_PENDING) continua a ter a limitação de bater sempre no 1.º Reel recebido visível — nada mudou nessa área desde a s27. Fica para PoC-8 iter 4.

### 2026-08-29 — Sessão 29 (Ricardo + Copilot CLI) — parte B validada, fix da parte C (ERR_UNKNOWN_URL_SCHEME)

- **Parte B (`ACTION_DISCOVER_REELS_HISTORY`) validada em device** — utilizador correu 3 testes na conversa "Pedro Sardoeira" (log em `docs/screen-dumps/feed.txt`):
  - **Run 1 (~15:43:10 → 15:43:12):** conversa já quase toda descoberta. `HISTORY: scroll=0 inserted=0 skipped=1` → scroll 1 e 2 idem. Parou com `HISTORY: stopping — 3 consecutive empty scrolls.` no fim, `finished — scrolls=2 totalInsertedRun=0 totalSkippedRun=2`. Comportamento correcto.
  - **Run 2 (~15:44:19 → 15:44:49, ~30s):** conversa longa. Correu até o cap: `HISTORY: scroll 30/30 via ACTION_SCROLL_BACKWARD accepted`, `HISTORY: stopping — safety cap 30 scrolls hit.`, `finished — scrolls=30 totalInsertedRun=36 totalSkippedRun=31`. **BD passou de 3 para 39 Reels** (36 novos + os 3 já lá). Ritmo médio ~1 Reel/scroll — óptimo.
  - **Run 3 (~15:45:24 → 15:45:27):** utilizador meteu outra app à frente a meio. Logcat: `HISTORY: IG no longer foreground during enumerate, stopping.` seguido de `finished — scrolls=2 totalInsertedRun=1 totalSkippedRun=1`. Cleanup correcto.
  - **Nada a mexer na parte B** — resolvida e validada.
- **Parte C falhou no primeiro teste** — utilizador reportou "página web não disponível", "não foi possível carregar a página web", URL a mencionar `reels_share`, motivo `net::ERR_UNKNOWN_URL_SCHEME`.
  - **Causa:** o Instagram detecta browser mobile e emite um redirect para `instagram://reels_share/<code>` (ou `intent://…#Intent;package=com.instagram.android;end`) como estratégia de "abrir na app". O WebView não sabe o que fazer com esses schemes, aborta a navegação e mostra a error page com `ERR_UNKNOWN_URL_SCHEME`.
  - **Fix (dois níveis) no `ReelPlayerActivity`:**
    1. Novo `FriendsReelsWebViewClient` com `shouldOverrideUrlLoading` que devolve `true` para qualquer scheme ≠ http/https. Isto "engole" o redirect e mantém a página HTTP renderizada.
    2. Se mesmo assim a página principal falhar (rede, 4xx/5xx, geoblock, etc.), `onReceivedError` (filtrado por `isForMainFrame` para não disparar em falhas de sub-recursos como uma imagem 404) chama um callback que actualiza state em Compose. Um overlay `LoadErrorOverlay` sobrepõe-se então ao WebView com:
       - Título "Não foi possível carregar o Reel na app"
       - Descrição do erro exacta do WebView
       - URL para debug
       - Botão grande **"↗ Abrir no Instagram nativo"** — abre o IG oficial (comportamento antigo, garantido a funcionar)
       - Botão outline **"↻ Tentar de novo"** — recarrega o URL na WebView (útil para erros transientes tipo rede)
  - Retry funciona porque mantemos referência ao WebView via `webViewRef` state.
- **`BUILD_TAG` bumped para `build=s29`.** Sem alteração de schema Room.
- **Ficheiros alterados:**
  - `ui/player/ReelPlayerActivity.kt` — reescrito com o WebViewClient custom + overlay Compose + retry + fallback para IG nativo.
  - `res/values/strings.xml` — `player_error_title`, `player_retry` novos.
  - `service/InstagramReaderService.kt` — `BUILD_TAG` bumped (única alteração — não mexi em executor/history).
  - `PROJECT_PROGRESS.md` — estado atual, PoCs status (parte B ✅, parte C 🚧 aguarda re-validação), este log da sessão 29.
- **Testes propostos ao utilizador** (após pull):
  - **C1 (repeat)** — abrir feed, tocar **"▶ Ver Reel aqui"** num card com URL. Cenários possíveis:
    - **(a) Reel carrega** — óptimo, parte C está fechada.
    - **(b) Overlay de erro aparece** — verificar que aparece o botão grande **"↗ Abrir no Instagram nativo"** e o outline **"↻ Tentar de novo"**. Tocar no primeiro → deve abrir o IG. Tocar no segundo → deve recarregar a WebView (pode voltar a falhar se for um problema estrutural do URL — nesse caso é aceitável, o utilizador tem sempre o fallback).
    - Se aparecer o mesmo `net::ERR_UNKNOWN_URL_SCHEME` que antes, é sinal de que o IG conseguiu emitir o redirect **antes** do primeiro paint (não passou por `shouldOverrideUrlLoading`) — nesse caso passamos a um Plan B: baixar o URL para uma página HTML própria com `<video>` embutido, ou aceitar que o player é só fallback e o botão principal do card volta a ser "Abrir no Instagram nativo".
  - **C2 (unchanged)** — o link secundário **"↗ Abrir no Instagram nativo"** no card do feed continua a funcionar como antes.
- **Nada mudou** na parte A (batching), no history-scroll (parte B), no comportamento das notificações ou nos primitivos PoC-4/5/6/7. Só o player.

### 2026-08-29 — Sessão 30 (Ricardo + Copilot CLI) — parte C: URL embed em vez do share URL

- **Resultado do teste C1 com o fix da s29:**
  - O `net::ERR_UNKNOWN_URL_SCHEME` desapareceu — o `shouldOverrideUrlLoading` a bloquear `instagram://` funcionou.
  - Mas o WebView carrega a página HTTP e mostra a **landing wall do IG mobile** com "Continuar na web" no canto superior direito. Só depois de tocar aí é que o Reel aparece. Debaixo do vídeo há "Abrir o Instagram" e opções de registo. Tocar no vídeo no meio do ecrã não faz nada. UX inaceitável para o utilizador — precisa de 2 taps só para ver o Reel.
- **Fix na s30 — usar o URL de embed oficial:**
  - O Instagram tem uma URL alternativa desenhada para incorporar Reels em sites externos: `https://www.instagram.com/reel/<shortcode>/embed`. Esta URL:
    - Não mostra a landing "Continuar na web"
    - Não pede login/registo no meio do fluxo
    - Renderiza um player minimal com autoplay
    - É o mesmo mecanismo que sites tipo Medium, Reddit, notícias usam para mostrar Reels
  - Nova função `toEmbedUrl(rawUrl)` — regex `instagram\.com/reels?/([A-Za-z0-9_-]+)` extrai o shortcode e reconstrói para `https://www.instagram.com/reel/<code>/embed`. Aceita `/reel/` (singular, novo) e `/reels/` (plural, mais antigo). Query params (`?igsh=...`) e trailing paths são descartados. Se a regex não bater, devolve o URL original (fallback silencioso).
  - `PlayerScreen` calcula `embedUrl = url?.let(::toEmbedUrl)` e passa isso ao WebView. Retry também recarrega o embed URL.
  - `LoadErrorOverlay` mostra ambas as URLs quando são diferentes — se a embed falhar, o utilizador vê o URL efectivo tentado + o URL original guardado na BD.
  - `openInInstagram` continua a usar o URL ORIGINAL (o URL cru guardado no `ReelEntity.reelUrl`) — porque é esse que abre bem no IG nativo.
- **Nota importante:** só o `ReelPlayerActivity` mudou. Comportamento do "↗ Abrir no Instagram nativo" (no card do feed ou no overlay) continua idêntico — usa o URL cru guardado na BD.
- **`BUILD_TAG` bumped para `build=s30`.** Sem alteração de schema.
- **Ficheiros alterados:**
  - `ui/player/ReelPlayerActivity.kt` — nova `toEmbedUrl`, `PlayerScreen` passa a usar embed URL, `LoadErrorOverlay` mostra ambas as URLs.
  - `service/InstagramReaderService.kt` — só `BUILD_TAG` bumped.
  - `PROJECT_PROGRESS.md` — estado atual, PoCs status, este log.
- **Testes propostos (só C1 novamente):**
  - Feed → **"▶ Ver Reel aqui"** num card com URL. Esperado:
    - **(a) Ideal:** aparece o vídeo do Reel a fazer autoplay num player minimal com o header "Reel de @autor" ou similar. Sem landing, sem "Continuar na web", sem login wall no meio.
    - **(b) Se falhar:** aparece o overlay de erro com o embed URL e o URL original — reportar para vermos se é problema de shortcode ou de regex.
    - **(c) Se aparecer landing ou "Continuar na web":** o IG mudou o comportamento do endpoint de embed — precisamos ir para o Plan B (§6.1).
  - "↗ Abrir no Instagram nativo" (no card ou no overlay) — deve abrir o IG oficial com o URL original.

### 2026-08-29 — Sessão 31 (Ricardo + Copilot CLI) — parte C: autoplay via JS

- **Resultado da s30 em device:** utilizador confirmou "agora realmente mostra como se estivesse no insta no computador". A landing "Continuar na web" desapareceu — o embed URL é o mecanismo certo. Único problema restante: "o vídeo não dá auto play, é preciso eu clicar nele e ele começa a dar". Ou seja: parte visual/UX perfeita, só falta autoplay.
- **Causa:** Chrome/WebView tem uma política de autoplay que bloqueia vídeos com som sem interacção prévia do utilizador com o domínio. `settings.mediaPlaybackRequiresUserGesture = false` sozinho não chega porque a política do Chrome é mais estrita. O vídeo aparece como poster/pausado até um tap. Um tap arranca porque conta como gesto.
- **Fix (s31):** injectar JS após `onPageFinished` que force `video.play()` em todos os `<video>` da página.
  - Estratégia: tenta unmuted primeiro (para som), se falhar dá `.catch()` que mete `muted = true` e volta a tentar (Chrome sempre permite autoplay muted).
  - Corre 3× (0ms, 600ms, 1500ms após onPageFinished) porque o embed do IG cria o `<video>` async — se corrermos só uma vez no `onPageFinished`, o `<video>` ainda pode não existir.
  - JS embutido como const na companion do `FriendsReelsWebViewClient` para não poluir o Kotlin.
- **`BUILD_TAG` bumped para `build=s31`.** Sem alteração de schema. Só o `FriendsReelsWebViewClient` mudou.
- **Ficheiros alterados:**
  - `ui/player/ReelPlayerActivity.kt` — `FriendsReelsWebViewClient` ganha `onPageFinished` + companion `FORCE_AUTOPLAY_JS`.
  - `service/InstagramReaderService.kt` — só `BUILD_TAG` bumped.
  - `PROJECT_PROGRESS.md` — estado atual, PoCs, quick start, este log.
- **Teste proposto (só um):**
  - Feed → **"▶ Ver Reel aqui"**. Esperado:
    - **(a) Ideal:** vídeo faz autoplay **com som** dentro de ~1s. Parte C fechada 🎉
    - **(b) Aceitável:** vídeo faz autoplay **muted** — houve fallback pelo Chrome bloquear com som. Utilizador pode dar unmute com um tap. Também é aceitável para o MVP.
    - **(c) Ainda pausado:** reportar — significa que o JS não conseguiu tocar o elemento. Vamos precisar de investigar o markup do embed do IG (possivelmente o `<video>` está dentro de um `<iframe>` cross-origin em vez de directamente no `document`).

### 2026-08-29 — Sessão 32 (Ricardo + Copilot CLI) — parte C fechada + limpeza de docs

- **Parte C validada** — utilizador confirmou "funciona abre e começa a dar e está a dar com som". Autoplay unmuted pela JS injection da s31 correu como esperado. **PoC-8 iteração 3 concluída por inteiro (A ✅ B ✅ C ✅).**
- **Limpeza de documentação:**
  - `PROJECT_PROGRESS.md` — "Estado atual" reduzido a uma linha. Quick start reescrito (a numeração estava partida, mencionava "5 botões" na notif desde a s27 e "sessões 20→27" nos ficheiros-chave). §6 lista de PoCs consolidada: entradas 🚧 das partes A/B/C removidas, resumo condensado numa linha por parte. §6.1 redirecciona para PoC-9 (navegação entre conversas) em vez de repetir testes já corridos. §6.2 encolhido — o item "PoC-9" saiu daqui (subiu para §6.1 como próximo passo real).
  - `README.md` — actualizado no fim desta sessão (sem `build=sNN` no corpo, só pointer para o PROJECT_PROGRESS).
- **`BUILD_TAG` mantido em `build=s31`** — nada mudou em código.
- **Próximo passo (na próxima sessão):** PoC-9 (deep-link `instagram://direct/t/<thread_id>` ou navegação por `header_title` a partir da inbox). Ver §6.1 para plano.

### 2026-08-29 — Sessão 33 (Ricardo + Copilot CLI) — PoC-9 (navegação entre conversas via `header_title`)

- **Contexto:** o executor de batching da s26/s27 marcava como FAILED qualquer step cuja `threadTitle` não batesse com a conversa activa (`"Conversa activa é 'X' mas a acção pertence a 'Y'"`), tornando o batching útil só dentro de UMA thread. §6.1 da s32 propunha duas abordagens: deep-link `instagram://direct/t/<thread_id>` (precisa investigar onde IG expõe o id) ou navegação por título via inbox (fallback pragmático).
- **Decisão nesta sessão:** implementar já a abordagem #2 (por título). Zero investigação em device necessária — o formato do `contentDescription` das rows da inbox já está mapeado em `IgSelectors.Inbox` desde a s3 (`"<name>, [não lidos, ]<preview> ·, <time>"`). Se a validação for tranquila, o deep-link fica como upgrade futuro.
- **Alterações em `InstagramReaderService.kt`:**
  - Novo bloco "PoC-9 — thread navigation helpers" antes do bloco de batching:
    - `currentHeaderTitle()` — live read do `header_title` na árvore actual. Substitui usos de `lastKnownConversationTitle` (que ficava stale após BACK) dentro do batching.
    - `isInboxVisible()` — heurística por presença do label localizado `IgSelectors.Inbox.TITLE_MESSAGES` em qualquer janela IG.
    - `clickDirectTab()` — click no botão `direct_tab` da bottom nav, sobe a árvore até um ancestor clickable.
    - `clickInboxRow(threadTitle)` — procura o nó cujo `contentDescription` começa com `"$threadTitle, "` (nota: o `, ` importa — é o separador do formato standard da inbox) e clica no ancestor clickable mais próximo.
    - `navigateToThreadAsync(target, attemptsLeft, onDone)` — state machine assíncrona. Cada attempt inspecciona o estado actual e dispara UMA acção (BACK, click direct_tab, click row), depois postDelayed(`NAV_STEP_SETTLE_MS`, retry). Termina em sucesso quando `currentHeaderTitle() == target`, ou em falha quando não há inbox row match / não há direct_tab / attemptsLeft esgota.
  - `applyPendingActions()` deixa de comparar `currentThread` uma vez no arranque. Reordena os `steps` agrupados por `threadTitle` (ordem entre grupos: earliest `createdAt`; ordem dentro do grupo: `createdAt ASC`) para minimizar navegações. Loga `resolved N step(s) across M thread(s) to run`.
  - `runBatchStep(steps, index)` — signatura simplificada (deixou de aceitar `currentThread`). Passou a ser o "gate": se `currentHeaderTitle() == target` corre directamente; senão chama `navigateToThreadAsync` e no callback de sucesso agenda `executeBatchStep` após `NAV_POST_ARRIVAL_SETTLE_MS`. Falha → step marcado FAILED com `"Não consegui navegar para 'X'"`, continua com o próximo step.
  - `executeBatchStep(steps, index)` novo — o antigo corpo de `runBatchStep` (dispatch da primitiva + agenda o próximo). Assume que a thread correcta já está aberta.
- **Novas constantes de timing** (companion object):
  - `NAV_STEP_SETTLE_MS = 1000L` — delay entre attempts de navegação. Cobre a animação BACK/slide-in do IG. Pago só quando é preciso trocar de conversa.
  - `NAV_MAX_ATTEMPTS = 10` — orçamento total de ~10s por step de navegação. Path típico é 3 attempts (arbitrário → direct_tab → inbox → row → confirm).
  - `NAV_POST_ARRIVAL_SETTLE_MS = 1500L` — grace period após chegada para a RecyclerView renderizar os bubbles antes do long-press. Espelha o `POST_LONG_PRESS_SETTLE_MS`.
- **`BUILD_TAG` bumped para `build=s33`.** Sem alteração de schema Room. Sem novos broadcasts.
- **Validação em ambiente do agente:** kotlinc parse-check (2.0.21 + JDK 21) sobre o `InstagramReaderService.kt` — nenhum erro de sintaxe/parse (só erros semânticos esperados por falta do Android classpath). Build real precisa do device do utilizador.
- **Ficheiros alterados:**
  - `service/InstagramReaderService.kt` — todos os helpers de navegação novos + refactor do executor + `BUILD_TAG=s33` + constantes de timing.
  - `PROJECT_PROGRESS.md` — Estado atual, quick start, §6/6.1/6.2 reescritas, este log.
- **Testes propostos ao utilizador** (após pull + reinstalar):
  - **N1 — batching numa única conversa (sanity).** Enfileirar 2-3 acções para uma conversa X. Abrir IG e ficar na conversa Y (ou na inbox). Tocar **▶ Aplicar fila**. Esperado: navegação para X (logs `NAV: attempt K stage=…`), depois execução normal. `currentHeaderTitle` no arranque deve reflectir onde o utilizador está.
  - **N2 — batching cross-thread.** Enfileirar 1 acção para X e 2 para Y. Tocar Aplicar. Esperado: `resolved 3 step(s) across 2 thread(s)`, executor arranca no grupo cuja acção é mais antiga, corre lá, navega para o outro grupo, corre lá.
  - **N3 — thread com título único mas dentro dum estado estranho** (ex.: IG na Home, ou noutra app). Confirmar que o `runInInstagram` primeiro traz o IG à frente e depois o `navigateToThreadAsync` completa. Uma vez ambos os passos feitos, restante execução normal.
- **Se algum teste falhar** (o step fica preso em `NAV: no inbox row starts with '<title>, '` ou o batch termina com FAILED):
  - **Hipótese A — formato do `contentDescription` mudou.** O IG pode ter passado a usar separador diferente ("," sem espaço, ou "•" em vez de "·"). Diagnóstico: pedir dump da inbox e ajustar o predicado (`clickInboxRow`).
  - **Hipótese B — thread title na BD tem trailing whitespace / diferente casing.** Fix rápido: normalizar ambos os lados (`.trim().lowercase()`).
  - **Hipótese C — `direct_tab` não é o resource-id certo neste build.** Fix: fallback para procurar a bottom nav pelo `contentDescription` ("Mensagens").
- **Nada mudou** nas primitivas PoC-3 a PoC-7 (long-press, reactions, reply, copy URL), no history-scroll (parte B), no player (parte C) ou na notificação persistente. Só o executor de batching + helpers de navegação.

### 2026-08-29 — Sessão 34 (Ricardo + Copilot CLI) — PoC-9 validado + PoC-8 iter 4 (targeting por Reel + scroll)

- **PoC-9 iter 1 validada em device.** Utilizador correu 4 batches consecutivos (log em `docs/screen-dumps/feed.txt`, timestamps 17:21→17:25):
  - Batches em uma única conversa: `NAV` só dispara quando IG está noutra thread. Todas as chegadas confirmadas por `NAV: arrived at 'X' (attemptsLeft=…)`.
  - Batch cross-thread (`resolved 2 step(s) across 2 thread(s)`): executor visitou Pedro Sardoeira, correu step 1, navegou para André Pinto, correu step 2. Ordem correcta.
  - Batches arrancados de `com.example.friendsreels`, `com.android.systemui` e outra thread — `runInInstagram` + `navigateToThreadAsync` compõem-se sem edge cases.
  - **Nada a mexer em PoC-9.**
- **Bug real identificado no log:** dentro da conversa correcta, a maioria dos steps termina com `LONG_PRESS: no eligible Reel bubble found (ignoreSent=true). Are you on a conversation with a received Reel visible?`. Quando IG abre uma thread aterra sempre no bottom (mensagens mais recentes); os Reels que o utilizador enfileirou estão em cima, fora do ecrã. `longPressFirstReel` só olhava para os bubbles visíveis — não fazia scroll para procurar. Além disso, mesmo quando havia bubbles visíveis, o executor pegava no 1.º recebido em vez de verificar se era o Reel certo.
- **Fix nesta sessão — PoC-8 iter 4:** localizar o Reel específico por `reelAuthor` + scroll backwards até encontrar.
  - Novo helper `dispatchLongPressOn(target: DmReelEntry, afterLongPress, windowBounds?)` — extraído da parte "já tenho target" do `longPressFirstReel`. Permite ao batching entregar o alvo já localizado.
  - Novo helper `locateReelWithScroll(target: ReelEntity, scrollsLeft, onDone)` — máquina de estados async recursiva:
    1. Enumera bubbles recebidos visíveis via `enumerateReels(messageList)`.
    2. Se `target.reelAuthor` bate com algum, chama `onDone(entry)` (top-most vence em caso de múltiplas do mesmo autor).
    3. Senão dispara `ACTION_SCROLL_BACKWARD` no `message_list`, espera `LOCATE_SCROLL_SETTLE_MS=800ms`, recorre.
    4. Cap de `BATCH_MAX_SCROLLS=20` (~16s per step). Se a11y scroll for refused (topo atingido) ou o cap esgotar, chama `onDone(null)`.
    5. Fallback: se `target.reelAuthor` for null (rows antigos pré-PoC-4), matcha o primeiro RECEIVED visível — comportamento igual ao antigo.
  - `executeBatchStep` refactorada — antes de disparar o long-press, chama `locateReelWithScroll(step.reel, BATCH_MAX_SCROLLS) { entry -> … }`. Se `entry != null` → `dispatchLongPressOn(entry, after)` + agenda próximo step com o delay habitual. Se null → step marcado FAILED com `Reel do @autor não encontrado após 20 scrolls (reelId=…)` e avança para o próximo.
  - `longPressFirstReel` mantém-se para os botões da notificação (que não têm target concreto — passam `null` e mantêm o comportamento "first eligible").
- **`BUILD_TAG` bumped para `build=s34`.** Sem alterações de schema Room.
- **Ficheiros alterados:**
  - `service/InstagramReaderService.kt` — `dispatchLongPressOn`, `locateReelWithScroll`, `executeBatchStep` refactorada, `BUILD_TAG=s34`, constantes `BATCH_MAX_SCROLLS`, `LOCATE_SCROLL_SETTLE_MS`.
  - `PROJECT_PROGRESS.md` — Estado atual, quick start, §6/6.1/6.2 actualizadas, este log.
- **Validação em ambiente do agente:** kotlinc parse-check OK. Build real precisa do OnePlus.
- **Testes propostos (na §6.1):** L1 (Reel visível — sanity), L2 (Reel fora do ecrã — o cenário que falhava na s33), L3 (múltiplos steps mesma conversa, autores diferentes), L4 (cross-thread com Reels antigos em ambas).
- **Limitação conhecida deste iter (documentada em Estado atual):** matching por `reelAuthor` — 2 Reels do mesmo criador IG partilhados na mesma conversa colidem (o top-most vence). Fix futuro é matching estrito por `reelUrl`, mas obriga a abrir o viewer para cada Reel durante o discover (lento). Fica para PoC-10.
- **Nada mudou** nas primitivas isoladas PoC-3/5/6/7, no history-scroll `discoverReelsHistory`, no player, na notificação, ou na navegação PoC-9. Só o executor de batching + o pipeline de long-press.

### 2026-08-29 — Sessão 35 (Ricardo + Copilot CLI) — iteração grande alinhada à visão

- **Feedback do utilizador na s34:** "de momento a aplicação está extremamente longe do que era a visão para a mesma". Pediu para fazer uma iteração grande sem testes intermédios, entregando um sistema mais próximo da spec, e depois pedir testes end-to-end.
- **Fizemos numa só iteração:**
  1. **Fallback swipe no `locateReelWithScroll`.** Se `ACTION_SCROLL_BACKWARD` for refused (topo do RecyclerView), o service faz swipe DOWN (mesma técnica de `discoverReelsHistory`) em vez de desistir imediatamente. Robustez extra para PoC-8 iter 4.
  2. **`FeedViewModel` — estados derivados por Reel.** Novo `ReelUiState(seen, pendingHeart/Laugh/Reply, reactedHeart/Laugh, replied, failedActions)` produzido por `combine(reelDao.observeAll(), pendingDao.observeAll())`. Deriva SEEN/REACTED/REPLIED da spec §7 a partir da tabela `pending_actions` (row DONE = reagido/respondido). `enqueueReply` deixa de ter texto fixo — recebe `text: String` do dialog.
  3. **Feed reescrito para `VerticalPager` full-screen (spec §3).** Cada Reel ocupa o ecrã inteiro. Hero central com botão play que abre `ReelPlayerActivity` (o WebView embed que já tínhamos). Metadata block com autor (@), remetente na DM, thread, data. Chips no topo: `recebido/enviado`, `visto`, `❤ reagido`, `😂 reagido`, `💬 respondido`. Row de acções `❤ / 😂 / 💬`.
  4. **Reply real com dialog (spec §5).** Toque em `💬 responder` abre `AlertDialog` com `OutlinedTextField` pré-preenchido com "👀". Botão "Enfileirar resposta" só activo com texto não-vazio. O texto vai para `PendingActionEntity.payload` e o service usa-o no `ACTION_SET_TEXT` do composer (já funcionava desde a s15, mas até agora só o mock fixo era enviado).
  5. **Menu 3-pontinhos (spec §12).** Ícone `MoreVert` no topo direito de cada page. Dropdown com "Abrir Reel no Instagram nativo", "Abrir conversa no Instagram", "Cancelar acções pendentes deste Reel", "Definições". O "abrir conversa" traz o IG à frente e mostra um Toast com o nome da thread (não conseguimos navegar directamente sem thread_id — spec §12 aceita fallback).
  6. **`SettingsActivity` novo (spec §11).** Toggles: "Ignorar Reels enviados por mim" (`PREF_IGNORE_SENT`), "Inverter direção do swipe" (`PREF_INVERT_SWIPE` novo). Placeholder para "Seleção de conversas" (spec §8 — próxima iteração). Secção "Ferramentas de diagnóstico" com os broadcasts directos que estavam antes na Home.
  7. **`FeedActivity` lê `PREF_INVERT_SWIPE`** — passa flag ao `FeedScreen`. Quando ativado, `orderedReels = reels.asReversed()`.
  8. **Home limpa.** Protagonismo ao `▶ Abrir o meu feed`. Secção Descoberta com `🔍 Descobrir Reels desta conversa` + `📥 Descobrir histórico`. Secção Configuração com `Ativar serviço de acessibilidade`, `Abrir Instagram`, `⚙ Definições`. Sai a poluição de 7 botões de diagnóstico (agora estão nas Definições).
  9. **`BUILD_TAG` bumped para `build=s35`.** Sem alteração de schema Room. Novos `PREF_INVERT_SWIPE` + `PREF_INVERT_SWIPE_DEFAULT` na companion.
- **Ficheiros novos:**
  - `ui/settings/SettingsActivity.kt`
- **Ficheiros alterados:**
  - `service/InstagramReaderService.kt` — swipe fallback no locate + `PREF_INVERT_SWIPE` + `BUILD_TAG=s35`
  - `ui/feed/FeedScreen.kt` — reescrito de LazyColumn para VerticalPager, novo `ReelPage`, `ReplyDialog`, 3-pontinhos
  - `ui/feed/FeedViewModel.kt` — `ReelUiState` derivado, `enqueueReply(text)` guarda o texto
  - `ui/feed/FeedActivity.kt` — lê `PREF_INVERT_SWIPE` e passa ao Composable
  - `MainActivity.kt` — Home reestruturada
  - `AndroidManifest.xml` — declara `SettingsActivity`
  - `res/values/strings.xml` — dezenas de strings novas (home_*, feed_chip_*, feed_action_*, feed_menu_*, feed_reply_dialog_*, settings_*, home_open_settings, etc.). Strings antigas mantidas com o mesmo id para não partir referências.
  - `PROJECT_PROGRESS.md` — Estado atual, quick start, §6/6.1/6.2 reescritas, este log, §8 nova com passos dos testes end-to-end
- **Validação em ambiente do agente:** kotlinc parse-check sobre todos os ficheiros Kotlin tocados — nenhum erro real de sintaxe (só erros semânticos esperados por classpath Android em falta).
- **Testes end-to-end propostos em §8.** Instruções passo-a-passo com os nomes exactos dos botões que o utilizador precisa de tocar. Modelo do utilizador: correr toda a bateria uma vez, capturar Logcat, e reportar visualmente o que aconteceu.

---


### 2026-08-30 — Sessão 36 (Ricardo + Copilot CLI) — auto-play inline + fixes de feedback

- **Feedback do utilizador na s35** (docs/screen-dumps/feed.txt + descrição visual):
  - E1, E2, E3, E4 passaram.
  - **E3 nuance:** IG substitui a reacção anterior. UI a acumular `reactedHeart` + `reactedLaugh` era confuso — mostrar apenas a actual.
  - **E5:** "Abrir conversa no Instagram" só fazia launcher intent + toast a dizer para o utilizador ir manualmente. Sem valor. Remover.
  - **E6:** não é prioridade — esconder toggle "Inverter swipe" até termos indicador visual da direcção no feed.
  - **E7:** falhou. Log mostra `NAV: click inbox row 'Pedro Sardoeira' returned true` repetidamente com `isInboxVisible()==true` MESMO ESTANDO na Home do IG. Causa: heurística antiga procurava texto "Mensagens" em qualquer nó — a Home do IG mostra "Notas" com previews de conversas cujos nomes incluem "Pedro" → clickInboxRow encontrava um nó com prefix e clicava, sem efeito. 10 attempts = ~10s perdidos por step.
  - **Vision reminder:** feed devia ser como o IG Reels (auto-play, swipe rápido), não tap-per-Reel. Tinha razão — a s35 tinha um botão de play central que exigia toque.
  - **Curiosity:** utilizador viu publicidade de app "Socialite" que reclama expor Reels de contas alheias. Pediu para investigar se a nossa Opção A/B (integração dentro do IG) é viável sem risco de ban.

- **Fixes implementados nesta sessão:**
  1. **Feed com WebView inline auto-play (`ui/feed/FeedScreen.kt`):**
     - Refactor: cada page do VerticalPager passa a hospedar um WebView via `AndroidView`.
     - Só o page current (via `pagerState.currentPage` ou `targetPage`) instantia o WebView; adjacentes ficam como Box preto minimal (leve).
     - Ao sair do page, `DisposableEffect` faz `stopLoading()` + `about:blank` + `destroy()` para libertar o Chromium.
     - Reels sem `reelUrl` mostram placeholder com título "URL ainda não capturado" + hint.
     - `DisposableEffect` marca `seenAt` do Reel assim que o page fica current pela 1.ª vez (spec §7).
  2. **Fix E7 — `isInboxVisible()` estrito** (`service/InstagramReaderService.kt`):
     - Passa a verificar `direct_tab.isSelected` em vez de matching de "Mensagens/Messages".
     - Elimina o falso positivo na Home do IG.
  3. **Fix E3 — `ReelUiState.currentReaction`** (`ui/feed/FeedViewModel.kt`):
     - Removidos `reactedHeart` e `reactedLaugh`. Novo `currentReaction: String?` = kind da última acção DONE de reacção (por `executedAt` DESC).
     - Chip único no topo: `❤ reagido` OU `😂 reagido`, nunca ambos.
     - `ActionRow` destaca o botão da reacção actual como "highlighted".
  4. **Fix E5 — menu 3-pontinhos** (`ui/feed/FeedScreen.kt`): removida a opção "Abrir conversa no Instagram" e a função `openThreadInInstagram`.
  5. **Fix E6 — esconder toggle** (`ui/settings/SettingsActivity.kt`): toggle "Inverter direção do swipe" removido do UI + assinatura da composable simplificada. Constantes `PREF_INVERT_SWIPE` mantidas no service; `FeedActivity` continua a ler o valor. Quando quisermos re-expor é uma linha.
  6. **Extracted `ui/player/EmbedPlayer.kt`** — helpers partilhados `buildReelWebView`, `FriendsReelsWebViewClient` (com o mesmo JS autoplay da s31), `toEmbedUrl`. Usados no feed inline e no `ReelPlayerActivity` (fallback). Reduz duplicação e centraliza o "know how" do embed.
  7. **`ReelPlayerActivity` reduzido** — mantém-se para o menu ⋮ ("Abrir Reel no Instagram nativo" abre o URL directo no IG; se o feed inline tem overlay de erro, o utilizador ainda pode abrir aqui como fallback).
- **`BUILD_TAG` bumped para `build=s36`.** Sem alteração de schema Room.
- **Ficheiros novos:**
  - `ui/player/EmbedPlayer.kt`
- **Ficheiros alterados:**
  - `service/InstagramReaderService.kt` — `isInboxVisible()` estrito, `BUILD_TAG=s36`
  - `ui/feed/FeedScreen.kt` — reescrito para inline WebView + `InlineReelPlayer` composable + chip único + menu sem "abrir conversa"
  - `ui/feed/FeedViewModel.kt` — `ReelUiState.currentReaction`
  - `ui/settings/SettingsActivity.kt` — remove toggle invertSwipe + assinatura
  - `ui/player/ReelPlayerActivity.kt` — reescrito, delega em `EmbedPlayer.kt`
  - `res/values/strings.xml` — novo `feed_no_url_yet_title`
  - `PROJECT_PROGRESS.md` — Estado atual, quick start, §6/6.1/6.2/6.3 reescritas + este log + §8 nova
- **Investigação em curso (background):** agente de research a compilar detalhes sobre a app Socialite e alternativas técnicas (Graph API vs scraping vs mobile-API-mimic). Resultados serão anexados em §9 quando disponíveis + integrados nas decisões de arquitectura.
- **Validação em ambiente do agente:** kotlinc parse-check sobre todos os 8 ficheiros Kotlin — nenhum erro real de sintaxe (só erros semânticos esperados por classpath Android em falta).

---

### 2026-08-30 — Sessão 37 (Ricardo + Copilot CLI) — enrichment on-demand + selecção de conversas

- **Contexto:** utilizador validou s36 (F1-F5 ok). Iteração grande sem testes intermédios. Duas features grandes para MVP: (a) URLs manuais eram o principal bloqueio da UX vision-aligned (a maioria dos Reels ficava com placeholder no feed) e (b) seleção de conversas (spec §8) faltava por completo.
- **Feature 1: On-demand URL enrichment (`ACTION_ENRICH_REEL_URL`)**
  - Novo broadcast com extra `reel_id`. Handler no serviço faz: loadReel → `runInInstagram` → `navigateToThreadAsync` → `locateReelWithScroll` → `dispatchOpenReelViewerTap` → chain existente PoC-7 (share sheet → copy link → clipboard bridge → `handleClipboardCaptured` → `promoteDiscoveryRow`).
  - Refactor de `openFirstReelViewer`: extraí `dispatchOpenReelViewerTap(bounds)` para reuso.
  - Novo `pendingCopy` é set pelo caminho de enrichment ANTES do tap → o clipboard resolve a row correcta.
  - `FeedViewModel.requestUrlEnrichment(reelId)` dispara o broadcast.
  - `FeedScreen.InlineReelPlayer`: placeholder ganha botão **"🔗 Preparar Reel"** que chama a acção + toast.
- **Feature 2: Seleção de conversas (spec §8)**
  - Nova entidade `TrackedThreadEntity` + `TrackedThreadDao` + DB v3→v4 (destructive migration — PoC data).
  - Nova pref `PREF_SELECTION_MODE` com 3 valores: `NONE`, `INCLUDE_ONLY`, `EXCLUDE_SELECTED` (default NONE).
  - `FeedViewModel.reels` passa a ser `combine(observeAll, trackedTitles, selectionMode)` que aplica o filtro.
  - Novo `SettingsViewModel` — expõe `selectionMode` (via SharedPreferences listener), `trackedTitles` (via DAO), `threadCounts` (join reels agrupado por threadTitle) e métodos `setSelectionMode` / `setTrackedThread(title, checked)`.
  - `SettingsActivity` reescrita: secção "Filtrar conversas no feed" com 3 radios (Ver tudo / Apenas selecionadas / Excluir selecionadas) + lista de threads descobertas com checkboxes e contagem de Reels por thread.
  - "?"-titled rows são filtradas do UI (rows antigas pré-PoC-4 sem título).
- **`BUILD_TAG` bumped para `build=s37`.** DB v3 → v4 (destructive). `PREF_SELECTION_MODE_DEFAULT = NONE` — na 1.ª abertura utilizador vê tudo, só sente diferença se for às Definições.
- **Ficheiros novos:**
  - `data/TrackedThreadEntity.kt`, `data/TrackedThreadDao.kt`
  - `ui/settings/SettingsViewModel.kt`
- **Ficheiros alterados:**
  - `service/InstagramReaderService.kt` — `ACTION_ENRICH_REEL_URL`, `EXTRA_REEL_ID`, `enrichReelUrl`, `startEnrichmentForReel`, `locateAndOpenReelViewer`, `dispatchOpenReelViewerTap` (extraído), prefs de selecção, `BUILD_TAG=s37`, log do receiver.
  - `data/AppDatabase.kt` — v4, adiciona `TrackedThreadEntity`.
  - `ui/feed/FeedViewModel.kt` — filtro por selection mode, `requestUrlEnrichment`, `selectionMode` StateFlow.
  - `ui/feed/FeedScreen.kt` — `onRequestUrl` callback, placeholder ganha botão "🔗 Preparar Reel" + toast.
  - `ui/settings/SettingsActivity.kt` — reescrita (radios + lista de threads).
  - `res/values/strings.xml` — 15 novos strings para selection + enrichment.
  - `README.md` — actualização do Fluxo + Definições (enrichment + selecção).
- **Validação em ambiente do agente:** kotlinc parse-check OK sobre todos os 12 ficheiros Kotlin.

---

## 8. Instruções para o Ricardo — testes end-to-end da s37

**Prep (1×):**
1. Pull do repo. Rebuild + reinstalar APK.
2. Abrir Friends Reels. **A BD foi apagada** (migração destrutiva v3→v4). Vai ter que descobrir Reels de novo.
3. Confirmar Logcat: `Action receiver registered (build=s37 ... enrichUrl=com.example.friendsreels.ACTION_ENRICH_REEL_URL)`.

### **G0 — Setup mínimo**

**Passos:**
1. Abrir uma conversa X no IG que tenha Reels partilhados por amigos.
2. Baixar notif → tocar **🔍**.
3. **NÃO** correr o 🔗 desta vez (queremos Reels sem URL para testar G1).
4. Voltar ao Friends Reels → **"▶ Abrir o meu feed"**.

**O que verificar:** feed aparece com Reels do X, todos com placeholder **"URL ainda não capturado"** + botão **"🔗 Preparar Reel"**.

### **G1 — Enrichment on-demand (novo na s37)**

**Passos:**
1. No feed, num Reel sem URL, tocar **"🔗 Preparar Reel"**.
2. Toast aparece: "A ir buscar o URL ao Instagram… mantém-te por aqui."
3. Ver visualmente:

**O que deve acontecer:**
- ✅ IG abre (se estava fechado), navega para a conversa correcta.
- ✅ Faz scroll para trás até encontrar o Reel (scroll é visível).
- ✅ Tap no bubble abre o Reel viewer.
- ✅ IG vai à partilha, copia a ligação, faz BACK.
- ✅ (Podes voltar manualmente ao Friends Reels ou esperar.)
- ✅ No feed, o placeholder desaparece e o **vídeo faz auto-play** para esse Reel.

**Logcat esperado:**
```
ENRICH_URL: starting for reelId=… author=… thread='…' current='…'
NAV: … arrived at '…' (se preciso)
LOCATE: matched reelId=… author=… ...
ENRICH_URL: located reelId=…, tapping to open viewer.
OPEN_REEL: tap gesture completed
SHARE_IN_VIEWER: performAction(ACTION_CLICK) on 'Partilhar' returned true
COPY_LINK: Reel URL = 'https://www.instagram.com/reel/…'
COPY_LINK: inserted row / promoted row …
```

**Se falhar:**
- Se `ENRICH_URL: reelId=… not in DB` → algum bug de sincronização; reportar `reel.id` do card.
- Se nav ok mas `LOCATE: could not find` → o Reel real foi apagado da conversa ou o autor mudou; aceitável.
- Se copy funciona mas o placeholder não desaparece do feed → problema de refresh do StateFlow; reportar.

### **G2 — Selecção "Apenas seleccionadas" (spec §8)**

**Passos:**
1. Ter Reels em pelo menos 2 conversas (X e Y).
2. Home → **⚙ Definições** OU 3-pontinhos → **Definições**.
3. Rolar até **"Filtrar conversas no feed"**. Deve ver os 3 radios e o modo por defeito **"Ver tudo"** selecionado.
4. Tocar em **"Apenas as selecionadas em baixo"**.
5. Aparece a lista de threads descobertas com checkboxes.
6. Marcar apenas a thread X (deixar Y desmarcada).
7. Tocar em **"Concluído"** (ou back).
8. Abrir o feed.

**O que verificar:**
- ✅ Só aparecem Reels da thread X.
- ✅ Fazer swipe para cima só passa entre Reels do X — Y não aparece.

### **G3 — Selecção "Excluir seleccionadas"**

**Passos:**
1. Definições → Filtrar conversas.
2. Tocar em **"Todas EXCEPTO as selecionadas em baixo"**.
3. Marcar a thread X.
4. Voltar ao feed.

**O que verificar:**
- ✅ Reels da thread X somem.
- ✅ Reels da thread Y (e outras) continuam a aparecer.

**Voltar ao normal:**
5. Definições → **"Ver tudo"**.
6. Feed volta ao completo.

### **F5 (regressão) — E7 refeito**

Só confirmar que o fix da s36 continua a funcionar:
1. Enfileirar 1 ação numa thread X.
2. Ir para a Home do IG.
3. Baixar notif → **▶ Aplicar fila**.
4. ✅ IG navega correctamente para X via `open-direct-tab`, aplica reacção.

### **F6 (regressão) — Fluxo base**

Confirmar em 30 segundos:
- Descobrir Reels + histórico ainda funciona.
- Reagir/responder via feed + ▶ Aplicar → aplica.

---

**Como enviar os resultados:**
- Copiar Logcat filtrado `IGReaderService` para `docs/screen-dumps/feed.txt`.
- Descrever brevemente por teste. Modelo aceite: **"G1 ok / G2 ok / G3 falhou porque X"**.


---

## 9. Investigação — "Socialite" e alternativas arquitecturais (s36)

**Contexto:** utilizador viu publicidade da app "Socialite" a reclamar acesso a Reels/Stories de contas alheias e YouTube Shorts. Pediu para investigar se a nossa Opção A/B (integração dentro do IG oficial ou API-like) é viável sem risco de ban. Agente de research lançado; resultados sumariados abaixo. Reporte completo em anexo.

### 9.1 O que é a "Socialite" na realidade

- **A app publicitada mais provável é "SocialLite – Block Reels & Shorts"** (Sociallite LLC, iOS only, bundle `social.Social-lite`, App Store ID 6757661674). 4.75★ / 6309 reviews. Fez o **oposto** do que o utilizador entendeu: **bloqueia** Reels/Shorts e mostra o resto do IG num modo focado sem distracções. Arquitectura inferida: WKWebView + injecção JS que remove os elementos de Reels/Explore/Shorts do DOM. Utilizador loga-se em instagram.com através do WebView (credentials vão para a Meta, não para a app). Sem API privada envolvida.
- **Não foi encontrada versão Android.** Um repositório GitHub `sharmakumaraditya/Socialites` existe (0 stars, wrapper Android de várias redes num único WebView com adblocker + VPN inbuilt) mas não é a app anunciada.
- **A frase "ver conteúdo de contas que não seguimos" é enganosa ou mal interpretada.** instagram.com no browser mostra Reels públicos de qualquer conta — não é magia técnica, é o comportamento normal do site. Qualquer app que reclame "ver conteúdo privado de contas alheias" e peça credenciais IG é quase certamente **credential harvester / scam**.

### 9.2 Vias técnicas reais para consumir IG (por ordem de risco)

| Método | Como funciona | Acesso a DMs | Risco de ban da conta | Barreira legal |
|---|---|---|---|---|
| **Graph API oficial** (Meta) | OAuth 2, contas Business/Creator | ❌ Não expõe DMs pessoais | Zero | Zero |
| **Private Mobile API** (instagrapi, instagram4j, okgram) | Mimica cliente app oficial via HTTP directo. Requer username+password OU cookie de sessão. | ✅ Acesso completo | **MUITO ALTO** — Meta detecta por TLS fingerprint, headers, padrão de tráfego, device ID. Common outcome: challenge → checkpoint → suspensão. | **Barinsta (2021)**: Meta enviou cease-and-desist ao dev, app retirada do F-Droid. Play Store / App Store rejeitam. |
| **Web scraping público** (`_gql` endpoints) | GraphQL/HTTP a instagram.com sem login | ❌ Só conteúdo público | Baixo (só IP-based) | ToS violation "collect via automated means" |
| **WebView wrapper com JS** (Frost-for-Facebook pattern) | Utilizador loga-se em m.instagram.com dentro dum WebView da app externa; JS injectado extrai / manipula o DOM. Credentials vão para a Meta. | ✅ DMs se o web IG expuser | **BAIXO** — Meta vê como browser normal, difícil distinguir server-side. | Play Store historicamente tolera (Frost tem 1.1k★ e sobreviveu anos). App Store mais frio. |
| **AccessibilityService** (a nossa **Opção C actual**) | Lê UI do IG oficial no device. Zero rede intermediária. | ✅ Tudo o que a UI mostra | **Zero server-side** — a Meta não consegue detectar. Único risco é o utilizador ficar sujeito a mudanças de UI. | Zero — nenhuma cláusula de ToS violada; é uma app externa a interagir com o device do utilizador. |

### 9.3 Bibliotecas relevantes (para futura consulta)

- **`instagram4j/instagram4j`** (Java, 1k★) — Private Mobile API, compatível com Android. Se algum dia decidirmos aceitar risco de ban.
- **`AllanWang/Frost-for-Facebook`** (Kotlin, 1.1k★) — arquitectura de referência para "WebView wrapper com JS extraction". Se decidirmos abandonar a11y a favor duma abordagem browser-based.
- **`subzeroid/instagrapi`** (Python, 6.7k★) — o wrapper mais mantido do Private API; docs excelentes sobre o que a Meta detecta.
- **`NiceDayZc/okgram`** (Python) — o único que faz reverse engineering completo do device fingerprint. Lista todos os headers relevantes (`IG-U-RUR`, `X-MID`, `IG-U-SHBID`, etc.) e explica porque é que sessão sozinha não chega.

### 9.4 Conclusão para o nosso projecto

A visão original da spec (Opção A: integração dentro do IG oficial) **NÃO é possível sem risco significativo**:

- **Modding do APK do IG** (Opção A pura) — requer LSPosed/Xposed, root, e viola a ToS + copyright. Ban imediato quando detectado. Não vamos por aqui.
- **App externa com Private API** (Opção B) — historicamente Meta persegue com C&D (caso Barinsta). Ban da conta do utilizador é comum. Nada garante que amanhã não haja detecção nova.
- **WebView wrapper (Opção A.2/B via browser embedded)** — é o que a Socialite real faz. Risco baixo, mas a UX perde-se: o utilizador teria de logar-se DE NOVO no IG dentro da nossa app (independente da sessão nativa) e ficamos limitados ao que o IG web expõe (que é menos rico que a app). DMs no IG web funcionam mas embed de Reels em DMs pode ser limitado.
- **AccessibilityService (Opção C actual)** — **é objectivamente a via mais segura para a conta** do utilizador. Meta não pode ban server-side (a interacção é local). Único cost: pediu-se explicitamente ao utilizador uma permissão sensível e o serviço tem de estar ligado.

**Recomendação:** manter a Opção C como direcção principal. **Ver §6.4 abaixo para reflexão sobre migração parcial para WebView em iterações futuras** (se o custo de manutenção da a11y aumentar demasiado com updates do IG).

### 9.5 Follow-up sugerido pelo agente

- Confirmar qual foi exactamente a app publicitada (screenshot ou nome exacto). Se for algo diferente de "SocialLite Block", verificar Play Store package e reviews antes de considerar como referência técnica.
- Se um dia quisermos experimentar Opção A.2 (WebView wrapper) como PoC alternativa, o padrão do **Frost-for-Facebook** é o ponto de partida: WebView + JS bridge + JavaScriptInterface para extrair URLs de vídeo do DOM.
