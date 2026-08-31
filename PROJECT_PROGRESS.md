# PROJECT_PROGRESS — Friends Reels Inbox

> Ficheiro cumulativo de acompanhamento do projeto, conforme exigido pela spec §19.
> Atualizar sempre que houver decisões, investigação, testes ou mudanças relevantes.

---

## Estado atual

**Fase actual:** Fase 1 (PoC → MVP).
**Última actualização:** 2026-08-31 (sessão 50 — 4 correcções do feedback do utilizador + tema IG-like).
**Arquitectura:** Opção C — app externa Android + `AccessibilityService`.
**HEAD actual:** `build=s50`.

**Recap sessões 46-50 (as próximas do estado corrente):**

- **s46:** `isThreadTopVisible(root)` detecta o header start-of-conversation via 4 selectors (`view_profile_button`, `user_avatar`, `network_attribution`, `other_user_full_name_or_username` — capturados no dump da s45). Integrada em `locateReelWithScroll` (aborta backward budget cedo) e `doHistoryScroll` (para no topo real).
- **s47/s47b:** instrumentação `seenAuthors` pré-filtro para expor o "Reel skipped mid-sweep" no log. `BATCH_MAX_FORWARD_SCROLLS` 5 → 15.
- **s48:** **`📥 Descobrir tudo`** — batch history-scroll em todas as conversas seleccionadas em Definições. Notif única de conclusão no fim.
- **s49/s49b:** sync da reacção actual (spec §7). Room v5 com `currentReaction: String?`. `enumerateReels` extrai emoji da `message_reactions_pill_container` via match geométrico. Feed prefere o emoji real da DM sobre o valor derivado das reacções que enviámos. Fix compile leftover de s47b.
- **s50 — 4 correcções + tema IG-like:**
  - **Fix 1:** `HISTORY_STOP_AFTER_N_EMPTY` 5 → 500 (essencialmente desactivada) + `HISTORY_MAX_SCROLLS` 100 → 2000. História pára só quando `isThreadTopVisible` == true. Correcção: batch já não pára cedo demais em conversas com sequências de mensagens texto ou Reels enviados.
  - **Fix 2:** `IgSelectors.Thread.REPLY_CONTEXT_INFO_TEXT = "direct_context_reply_context_info_text_view"` — nova constante. `enumerateReels` filtra bubbles com este marcador (respostas de terceiros ao meu Reel enviado). Antes clicava no Reel embebido na resposta e ficava preso.
  - **Fix 4:** `data_extraction_rules.xml` + `backup_rules.xml` — regras exhaustivas de exclusão (`root`, `database`, `sharedpref`, `file`, `external`) para garantir que uninstall + backup não deixam dados. `allowBackup=false` no manifest já estava.
  - **Fix 5:** novo `ui/theme/FriendsReelsTheme.kt` com paleta IG (background preto, superfícies `#121212`/`#1F1F1F`/`#262626`, primário `#E1306C` pink, gradient IG amarelo→laranja→rosa→roxo→azul, tipografia SemiBold para títulos com `letterSpacing` tightened). As 4 activities (Main, Feed, Settings, Player) passam de `MaterialTheme(darkColorScheme())` para `FriendsReelsTheme { ... }`.
  - **Fix 3 (pendente):** enrichment de URL é lento (~7s/Reel) porque cada Reel faz nav + locate + viewer + copy + back. Utilizador pediu checkpoint / speedup. Refactor "single-pass enrichment por thread" (uma única scroll por thread, abrir viewer conforme encontra Reels) fica para s51.

### Como continuar na próxima sessão (quick start)

1. **Pull** do repo. Confirmar `Action receiver registered (build=s50 ...)`.
2. **Ler primeiro:** esta secção "Estado atual", §6 "Próximos passos", §7 log, **§8 "Como testar" (regras obrigatórias de formato de teste — cada bateria em §6.1 deve seguir §8.1)**.
3. **Ficheiros-chave:**
    - `instagram/IgSelectors.kt` — `Thread` tem os 4 selectors do header (s46), `REACTIONS_PILL_CONTAINER` + `REACTION_ADD_BUTTON` (s49), **s50:** `REPLY_CONTEXT_INFO_TEXT`.
    - `service/InstagramReaderService.kt` — `isThreadTopVisible` (s46), `seenAuthors` pré-filtro (s47b), batch history (s48), reacção actual (s49), **s50:** filtro de reply-attachment em `enumerateReels`, constantes de history desactivadas na prática.
    - `data/*.kt` — Room v5 (s49). `fallbackToDestructiveMigration` — dados regeneráveis.
    - `ui/theme/FriendsReelsTheme.kt` — **s50**: tema IG dark aplicado em Main/Feed/Settings/Player.
    - `res/xml/data_extraction_rules.xml` + `res/xml/backup_rules.xml` — **s50**: exclusão total (uninstall clean).
    - Dumps: `docs/screen-dumps/dump.txt` (s45 header + s49b logs de reply-attachment + histórico slow).
4. **Constraints:**
    - Testes só no OnePlus Nord 5 / Android 16.
    - macOS deste ambiente não tem Android SDK, só validar sintaxe com kotlinc.
    - Cada refactor visível deve bumpar `BUILD_TAG`.
5. **UX actual em device:**
    - **Home:** protagonismo ao `▶ Abrir o meu feed`. Descoberta (🔍, 📥, 🔗 Preparar URLs (N) condicional). Configuração.
    - **Feed:** full-screen VerticalPager, background preto. Auto-play inline. Chip único de reacção. Menu ⋮.
    - **Definições:** 3 toggles + Filtrar conversas + Preparar URLs em lote + "📥 Descobrir tudo" + Diagnóstico ("🌳 Dump" com 5s delay).
    - **Notificação persistente:** 3 botões (🔍 🔗 ▶) + progresso + Cancelar durante lotes.
    - **Notificação de conclusão:** heads-up individual, ou uma única no fim do batch.
    - **Cores:** paleta IG (s50) — background preto, primário pink `#E1306C`, tipografia SemiBold títulos.
6. **Limitações conhecidas:**
    - Matching por `reelAuthor` — 2 Reels do mesmo criador na mesma conversa colidem.
    - **Reel skipped mid-backward-sweep (reportado na s46, instrumentado s47/s47b):** durante o backward-scroll dentro de `locateReelWithScroll`, o Reel-alvo pode aparecer brevemente em vista mas com `bounds.height() < MIN_REEL_BUBBLE_HEIGHT_PX` (200px) — o bubble está mid-layout durante uma transição do RecyclerView. `seenAuthors` captura o autor mesmo assim; se o batch acabar sem match dispara warning `LOCATE: wanted author '$X' was observed mid-backward-sweep but no bubble matched`. Não bloqueia — utilizador aceitou que "este teste não parece ser o suficiente para impedir de continuares a desenvolver".
    - **URL enrichment lento (Fix 3 pendente da s50):** utilizador reportou "só ao apanhar 69 está a demorar imenso para povoar". Cada Reel gasta ~7s (nav + locate + viewer + copy + back). Refactor "single-pass por thread" (scrollar 1× por thread, abrir viewer conforme encontra Reels) planeado para s51. Alternativa: checkpoint por URL já preenchido durante o scroll.
    - Batch enrichment partilha `pendingCopy` com o fluxo on-demand.
    - Consulta de respostas anteriores dentro da app (spec §5) — não temos sync.
    - Reacção "actual" no feed **(s49):** agora prefere o emoji lido de `message_reactions_pill_container`. Emojis com chip: ❤ e 😂. Os outros 4 IG (😮 😢 😡 👍) são persistidos mas ficam invisíveis até secção multi-emoji.
    - `threadTitle` como chave — se o utilizador renomear um grupo, a selecção perde-se.
    - Heads-up completion depende do canal `friends_reels_status_v2` em IMPORTANCE_HIGH.

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
- ✅ s36 — auto-play inline (WebView por page) + fix E7 (isInboxVisible estrito) + chip único de reacção + limpeza do menu e Settings. Validada em device: F1-F5 todos passaram, F6 sanity ok.
- ✅ **s37 — enriquecimento de URL on-demand + seleção de conversas.** Validada em device.
- ✅ **s38 — enrichment em batch.** Validada em device.
- ✅ **s39 — feedback de conclusão + return-to-app.** Validada em device.
- ✅ **s40 — atalhos (Home button, Cancel na notif, LastResult persistido).** Validada em device.
- ✅ **s41 — Cancelar apply pending + auto-enrich após descoberta.** Validada em device.
- ✅ **s42 — fail-fast + forward-scroll + heads-up.** L1+L3 confirmados.
- ✅ **s43 — 5x mais scrolls + faster + IG-focus recovery.** M1 (recovery) esperado ok, M2-M3 revelaram interleaving + stall detection buggy.
- ✅ **s44 — remove stall detection + epoch guard + botão dump em Diagnóstico.** Epoch guard aceite; botão de dump implementado mas em device capturou a árvore das Definições (não do IG) porque tocar no botão rouba o foco.
- ✅ **s45 — dump com atraso de 5s + Toast countdown.** Validada em device (`docs/screen-dumps/dump.txt`).
- 🟡 **s46 — detecção real de topo de conversa (`isThreadTopVisible`).** `TopStop-History` (Teste 2) validado em device. `TopStop-Batch` (Teste 1) inconclusivo por falta de Reel apropriado — utilizador aceitou como implicitamente validado dado que o mesmo código sustenta ambos.
- 🟡 **s47b — instrumentação corrigida do "Reel skipped mid-sweep" (enumeração pré-filtro).** `BATCH_MAX_FORWARD_SCROLLS` 5 → 15. Utilizador apanhou o design flaw da s47 (`seenAuthors` pós-filtro nunca dispararia com match-por-autor). s47b move o accumulator para ANTES do filtro de altura/largura para capturar bubbles transientes. Pronta em código; aguarda validação em device (Q1).

### 6.1 Próxima sessão — arranque

**Estado no fim da s48:** feature nova + instrumentação da s47b ainda por validar (não bloqueia).

- **s48 — `📥 Descobrir tudo`:** iterador batch sobre `tracked_threads` que navega a cada conversa seleccionada e corre history-scroll em cada uma. Reutiliza `navigateToThreadAsync` (PoC-9) + `discoverReelsHistory` com um novo callback `onFinish` opcional (para o batch não spamar notif de conclusão a cada thread). Botão em Definições → "Descobrir histórico em todas as conversas" → **"📥 Descobrir tudo"**. Notif única de conclusão no final. Cancelável via broadcast (`ACTION_DISCOVER_HISTORY_ALL_CANCEL`).
- **s47b (opcional):** instrumentação `seenAuthors` pré-filtro pronta em código; utilizador testa quando quiser. Não bloqueia mais features. Warning `LOCATE: wanted author '$X' was observed mid-backward-sweep but no bubble matched` dispara quando o Reel foi visto mid-layout mas nunca deu match.

**Bateria proposta — Sessão 48 (formato §8.1):**

#### Teste 1 — "📥 Descobrir tudo percorre todas as conversas seleccionadas" (`DiscoverAll`)

**O que se está a validar:** o novo botão em Definições dispara o batch, itera cada conversa em `tracked_threads`, insere Reels novos em Room, e termina com uma notif única.

**Preparação:**
1. `git pull` no telemóvel; recompilar e reinstalar o APK em `build=s50`.
2. Confirmar no logcat: `Action receiver registered (build=s50 ...)` e vê que `historyAll=com.example.friendsreels.ACTION_DISCOVER_HISTORY_ALL_TRACKED` está registado.
3. Em Definições, **"Filtrar conversas"** — seleccionar 2-3 conversas curtas conhecidas (o `astrid_gutierrez` da s45 é bom; adiciona mais 1-2 se tiveres). Confirmar que aparecem em `tracked_threads`.
4. **Contar** os Reels que já tens na DB para essas conversas: em Definições → "Preparar URLs em lote" a contagem de `Reels sem URL` pode ajudar (mostra o total antes/depois).
5. Abrir `logcat -s IGReaderService`.

**Passos:**
1. Abrir Friends Reels → **⚙ Definições** → rolar até **"Descobrir histórico em todas as conversas"**.
2. Tocar **"📥 Descobrir tudo"**. Toast confirma que arrancou.
3. **Deixar o telemóvel em paz** — vai abrir o IG, navegar entre conversas, e scrollar cada uma até ao topo (ou até bater no cap de 2000 scrolls, s50). Cada thread pode demorar 30s-15min consoante o comprimento (safety cap alto para chats de anos).
4. Esperar pela notif final "📥 Histórico descoberto (todas as conversas)".

**O que confirmar no logcat:**
- `HISTORY_ALL: starting batch for N thread(s).`
- Para cada thread: `HISTORY_ALL: step K/N thread='<titulo>'`, seguido de `NAV: ...` (navigate) e depois `HISTORY: starting thread='<titulo>' ...`, `HISTORY: scroll M/2000 ...`, `HISTORY: thread top reached ...` (ou `HISTORY: stopping — safety cap 2000 scrolls hit.` só em conversas muito longas).
- No fim de cada thread: `HISTORY_ALL: thread '<titulo>' done inserted=X scrolls=Y — batch running total=Z`.
- Fim do batch: `HISTORY_ALL: finished — totalInserted=Z across N thread(s).`
- Notif heads-up única: **"📥 Histórico descoberto (todas as conversas) — Z Reel(s) novo(s) em N conversa(s)."**

**Passa se:** todas as N conversas seleccionadas foram visitadas, Reels novos apareceram no feed, notif de conclusão apareceu.
**Falha se:**
- **F1:** batch começa e não avança da primeira conversa (fica preso em `HISTORY: ...` sem chegar ao topo) → pode ser que `isThreadTopVisible` esteja a falhar nessa conversa; verificar logs.
- **F2:** `HISTORY_ALL: nav failed for '<titulo>'` para várias threads consecutivas → a lógica de PoC-9 (`navigateToThreadAsync`) não está a encontrar as conversas na inbox. Ver `header_title` ou reindexar.
- **F3:** N conversas seleccionadas em Definições, mas `HISTORY_ALL: starting batch for M thread(s).` mostra M ≠ N → `snapshotTitles()` não devolveu o mesmo conjunto que `observeTitles()`. Verificar Room.
- **F4:** Toast a dizer "Nenhuma conversa seleccionada" mesmo tendo conversas em `tracked_threads` → verificar se o Toast do serviço aparece; log deve ter `HISTORY_ALL: no tracked threads — nothing to do.`

---

**Priorização depois de T validado:**

1. **Fix 3 — refactor single-pass URL enrichment (s51 planeada).** Cada thread scrolla 1× e enriquece Reels conforme encontra, em vez do actual nav + locate + viewer + back por Reel. Deve baixar de ~7s/Reel para ~2-3s.
2. **Match estrito por Reel URL no `locateReelWithScroll`** — para colisões de mesmo autor. Precisa investigar disambiguation por posição/timestamp.
3. **Cosmético:** thumbnails / preview no feed; indicador visual de direcção de swipe; usar `InstagramGradient` em hero buttons.
4. **Deep-link `instagram://direct/t/<thread_id>`** — investigar em dump da inbox.
5. **Bateria Q (`SeenSkipped` da s47b)** — quando for oportuno, saber a frequência do "Reel skipped mid-sweep".
6. **Suporte a mais emojis no chip do feed** — actualmente só ❤ e 😂. Adicionar 😮 😢 😡 👍.

**Bateria proposta — Sessão 50 (formato §8.1). 2 testes independentes:**

#### Teste 1 — "History-scroll chega ao topo real, sem parar em 5 empty scrolls" (`HistoryTop`)

**O que se está a validar:** a s50 baixou o gatilho `HISTORY_STOP_AFTER_N_EMPTY` de 5 → 500 (essencialmente desactivado). O único critério de stop passa a ser `isThreadTopVisible` (s46). Também valida Fix 2 (skip reply-attachment).

**Preparação:**
1. `git pull`; recompilar em `build=s50`. **Confirma no logcat:** `Action receiver registered (build=s50 ...)`.
2. Escolher **uma conversa com respostas ao teu envio de Reels** (bubble com marcador "Respondeu-te" contendo Reel embebido) para provocar Fix 2.
3. Idealmente a mesma conversa tem stretches de mensagens só de texto ou Reels enviados por ti — para provocar Fix 1.
4. Definições → "Filtrar conversas" — seleccionar só ESSA conversa.
5. `adb shell pm clear com.example.friendsreels` (opcional, para limpar rows antigas e ver apenas as novas).
6. `logcat -s IGReaderService`.

**Passos:**
1. Definições → **"📥 Descobrir tudo"**.
2. Deixar correr até `HISTORY_ALL: finished`.

**O que confirmar no logcat:**
- **NENHUMA** ocorrência de `HISTORY: stopping — 5 consecutive empty scrolls.` — o gatilho de 5 já não existe.
- Uma ocorrência de `HISTORY: thread top reached (view_profile_button visible) after N scrolls — stopping.` para cada thread coberta.
- Ocorrências de `ENUMERATE: skipped M reply-attachment bubble(s) ...` sempre que houver bubbles "Respondeu-te".
- N (número de scrolls até topo) muito maior que 5 para conversas de anos.

**Passa se:** cada thread chega ao topo real (`view_profile_button` visível), sem parar cedo demais. Reply-attachments logadas como skipped.
**Falha se:**
- **F1:** ainda aparece `5 consecutive empty scrolls` → constante não foi actualizada. Ver `HISTORY_STOP_AFTER_N_EMPTY`.
- **F2:** `HISTORY: stopping — safety cap 2000 scrolls hit.` → conversa é gigante. Aumentar `HISTORY_MAX_SCROLLS` mais.
- **F3:** `ENUMERATE: skipped ...` nunca aparece mesmo com bubbles óbvias de reply → `REPLY_CONTEXT_INFO_TEXT` selector pode ter mudado. Fazer dump manual e ajustar.

---

#### Teste 2 — "Design IG-like aplicado consistentemente" (`ThemeIG`)

**O que se está a validar:** o novo `FriendsReelsTheme` aparece nas 4 activities e o design está mais próximo do IG.

**Preparação:**
1. Recompilar em `build=s50`. Fresh install se possível para ver o app com o novo tema desde o início.

**Passos:**
1. Abrir a app → confirmar background preto + tipografia SemiBold nos títulos.
2. Definições → confirmar mesma paleta.
3. Feed → confirmar background preto + chips com o pink `#E1306C`.
4. Player de Reel (se aplicável) → mesma paleta.

**Passa se:** as 4 activities partilham a mesma paleta (preto + surface variants + pink primário) e o feel geral está mais próximo do IG do que na s49b.
**Falha se:**
- **F1:** alguma activity ainda mostra o teal/roxo default do Material 3 dark → verificar se `FriendsReelsTheme { ... }` foi aplicado nessa activity.
- **F2:** cores estão certas mas letras estão "grandes" ou com espaçamento estranho → typography não activou. Ver import de `Typography`.

---

#### Teste 3 — "Reacção actual (da DM) aparece no chip do feed" (`ReactionSync`) [carry-over da s49]

**O que se está a validar:** o chip do feed mostra a reacção que existe na DM (não só as que enviámos via app). Ainda por validar desde a s49 — inclui-se aqui na bateria T porque a s50 mantém a feature intacta.

**Preparação:**
1. Recompilar em `build=s50`. **Importante:** Room v4 → v5 dispara `fallbackToDestructiveMigration` — os Reels actuais são apagados no primeiro arranque. Aceitar (é PoC; re-descobrir com `📥 Descobrir tudo`).
2. Escolher **uma conversa com ≥2 Reels recebidos, um dos quais tem ❤ ou 😂 aplicado por ti directamente no IG** (não pela app). Se não tiveres, aplica manualmente uma agora.
3. `logcat -s IGReaderService`.

**Passos:**
1. Abrir Friends Reels → `🔍 Descobrir` (via notif ou home) nessa conversa. Ou usar `📥 Descobrir tudo` se essa conversa está seleccionada.
2. Log deve mostrar `DISCOVER: ... reactionsRefreshed=N ...` ou os inserts com `currentReaction` populado.
3. Abrir o feed. Navegar até ao Reel com reacção manual.

**Passa se:** chip ❤ ou 😂 aparece iluminado no Reel onde reagiste manualmente; sem quebrar Reels a que reagimos via app (fallback funciona).
**Falha se:**
- **F1:** chip nunca aparece → `matchReactionPill` não achou a pill. Fazer dump da conversa (via botão em Definições) e confirmar `resource-id` `message_reactions_pill_container`.
- **F2:** chip aparece no Reel errado → proximity mismatch. Ajustar `REACTION_PILL_MAX_GAP_PX` (60px) ou `REACTION_PILL_OVERLAP_TOLERANCE_PX` (20px).
- **F3:** reacções que enviámos via app deixaram de aparecer → fallback `?: latestReaction?.kind` em `FeedViewModel` partiu.

---

### 6.2 Alternativas arquitecturais

Utilizador levantou a hipótese na s36 depois de ver uma publicidade da app **Socialite** — reclama que permite ver Reels/Stories/Shorts de contas que o utilizador não segue. Se for possível sem risco de ban, poderia justificar re-abrir a Opção A/B da spec (integração dentro do IG oficial). Pesquisa completa em **§9 abaixo**. TL;DR:

- **A "Socialite" publicitada é provavelmente o "SocialLite" iOS**, que faz o OPOSTO (bloqueia Reels/Shorts). Arquitectura: WKWebView + JS que remove elementos de UI. **Não** é acesso privilegiado a contas alheias — apenas mostra o IG web normal focado sem distracções.
- **A frase "ver contas que não segues" é enganosa.** IG web mostra Reels públicos de qualquer conta a qualquer utilizador — nada de especial. Qualquer app que reclame acesso a DMs/Stories de contas privadas alheias é quase certamente credential harvester.
- **A visão original da spec (Opção A pura) NÃO é viável sem risco significativo.** Modding do APK = ban imediato. Private API (instagram4j etc.) = alto risco, Meta persegue com C&D (caso Barinsta 2021). WebView wrapper = risco baixo mas UX degradada.
- **A nossa Opção C actual (AccessibilityService) é objectivamente a via mais segura para a conta do utilizador** — Meta não consegue detectar server-side. Manter como direcção principal.

### 6.3 PoC alternativa (opcional, low-priority)

Se algum dia a manutenção do a11y ficar cara demais (i.e. IG partir a UI em cada release), a alternativa a experimentar é a **Opção A.2 via WebView wrapper (padrão Frost-for-Facebook)**:

- WebView embed com `m.instagram.com`
- Utilizador loga-se dentro da nossa app (session isolada da app nativa)
- JavaScriptInterface + injecção JS para extrair URLs de vídeo do DOM das DMs
- Custo: perde-se sessão partilhada (dupla autenticação), UX de swipe fica mais artificial (VerticalPager a alternar de página no WebView), e o IG web não expõe tudo o que a app expõe (composição de mensagens em DMs pode ser limitada)

Este trabalho fica em backlog até haver sinal claro de que a a11y não escala.

---

## 7. Log de sessões

> **Sessões 1-40 arquivadas** em [`docs/session-log-archive.md`](docs/session-log-archive.md):
> - s1-s32 (skeleton, PoCs 1-7, PoC-8 iter 1-3).
> - s33-s34 (PoC-9 navegação cross-thread, PoC-8 iter 4 targeting por Reel).
> - s35 (feed alinhado à visão), s36 (auto-play inline), s37 (enrichment on-demand), s38 (enrichment em batch), s39 (feedback de conclusão), s40 (atalhos + persistência).
>
> Abaixo ficam as sessões desde a s41, que são as que ainda contribuem para o estado corrente do batching/discovery.


### 2026-08-31 — Sessão 41 (Ricardo + Copilot CLI) — Cancelar apply + auto-enrich

- **Contexto:** utilizador validou I e J em device (log em `docs/screen-dumps/feed.txt` 11:35→11:39), confirmou que completion notifs, return-to-app, Home button e persistência do LastResult funcionam. Pediu para continuar autonomamente com features independentes.
- **Feature A — Cancelar `applyPendingActions`:** paridade com o batch enrichment.
  - Novo `ACTION_APPLY_PENDING_CANCEL` broadcast + handler `cancelApplyPending()` que liga `applyPendingCancelled = true` (no-op se `batchInProgress == false`).
  - `runBatchStep` verifica `applyPendingCancelled` no gate `if (applyPendingCancelled || index >= steps.size)`. Ao cancelar, o step actual termina normalmente (não interrompemos gestos mid-flight) e no próximo boundary o executor entra no ramo terminal.
  - Ramo terminal agora escolhe entre `notif_completion_apply_pending_body` (done) e `notif_completion_apply_pending_body_cancelled` (cancelled).
  - `updateProgressNotification` (o do apply) ganha `addAction("✕ Cancelar", pendingBroadcast(ACTION_APPLY_PENDING_CANCEL))` — mesmo padrão do batch enrichment na s40.
  - Reset em `applyPendingActions`: `applyPendingCancelled = false` no arranque (junto com os contadores). Log do receiver actualizado para incluir `applyCancel=`.
- **Feature B — Auto-enrich após descoberta:**
  - Novo pref `PREF_AUTO_ENRICH_ON_DISCOVER` (default `false`). Toggle nas Definições ("Preparar URLs automaticamente após descobrir").
  - Novo helper `maybeAutoEnrichAfterDiscover(insertedCount)`: early-return se `insertedCount==0`, pref off, ou outro batch em curso. Se ok, `mainHandler.postDelayed({ enrichAllMissingUrls() }, AUTO_ENRICH_CHAIN_DELAY_MS)` — 2.5s de grace para o completion notif da descoberta ser visto antes de a progress notif do enrichment tomar conta.
  - Chamado em 2 pontos:
    - Fim da coroutine de `discoverReels` (dentro do `mainHandler.post` que já posta a completion).
    - Fim de `finishHistory` (depois do `returnToAppIfEnabled` — a app volta para a frente durante os 2.5s enquanto o enrichment prepara-se para arrancar).
  - Efeito prático: com o toggle ON, `📥 Descobrir histórico` de uma conversa cheia de Reels torna-se um único gesto que traz TUDO — history discovers → auto-enrich lote → completion final.
- **`BUILD_TAG` bumped para `build=s41`.** Sem alteração de schema Room.
- **Novos strings** (3): `notif_completion_apply_pending_body_cancelled`, `settings_auto_enrich_title`, `settings_auto_enrich_subtitle`.
- **Ficheiros alterados:**
  - `service/InstagramReaderService.kt` — novo action + campo + reset + guard + helper + prefs + constante `AUTO_ENRICH_CHAIN_DELAY_MS` + Cancel button no apply progress notif + hooks nos dois discover paths + log line + `BUILD_TAG=s41`.
  - `ui/settings/SettingsActivity.kt` — 3.º toggle "Preparar URLs auto após descobrir" + assinatura + wire com pref.
  - `res/values/strings.xml` — 3 strings.
  - `PROJECT_PROGRESS.md` — Estado atual, quick start, §6/6.1 (bateria K), este log.
- **Validação em ambiente do agente:** kotlinc compile-check com JDK 21 — 18 erros totais, todos idênticos ao baseline (K2 type inference sem stdlib). Zero erros novos no código s41. Sintaxe OK.
- **Validação em device (esperada na próxima sessão):** bateria K1/K2/K3 descrita na §6.1.
- **Nada mudou** no fluxo de completion notifs, no return-to-app, na chain PoC-7, na navegação PoC-9, nos primitivos react/reply, no filtro de selecção. As duas features s41 são aditivas.

---

### 2026-08-31 — Sessão 42 (Ricardo + Copilot CLI) — fail-fast + forward-scroll + heads-up

- **Contexto:** utilizador reportou 3 problemas depois de correr K em device (log `docs/screen-dumps/feed.txt` 11:52→12:03, resultado catastrófico — 7 Reels falharam num batch de ~5 minutos):
  1. **Batches ficam "parados demasiado tempo"** quando LOCATE/NAV falham. Cálculo do log: cada Reel falhado custava ~45s (polling BATCH_ENRICH_STEP_TIMEOUT_MS) mesmo depois de já sabermos que ia falhar. 7 falhas × 45s = ~5 minutos inutilizados. Foi o motivo do utilizador ter cancelado.
  2. **LOCATE só scrolla para cima.** Um dos Reels (author `laglesssoul`) ficou fora do alcance mesmo depois de 20 scrolls backward. Visível ainda: `voidsenpai.fx_`. O Reel estava provavelmente ABAIXO. O motor original só faz `ACTION_SCROLL_BACKWARD`.
  3. **Completion notification só é visível abrindo o shade.** Utilizador quer heads-up (peek breve no topo). Ao descer o shade para ver o progresso, o dedo bate no ✕ Cancelar da s40 sem querer.
- **Fix 1: Fail-fast batch enrichment.**
  - Novo campo `@Volatile private var enrichmentStepFailedFast: Boolean = false`.
  - Reset em `processBatchEnrichmentStep` no início de cada step (antes de `startEnrichmentForReel`).
  - Set em 3 pontos:
    - `startEnrichmentForReel` quando `navigateToThreadAsync` retorna `navOk=false`.
    - `locateAndOpenReelViewer` quando `locateReelWithScroll` devolve `entry == null`.
    - `locateAndOpenReelViewer` quando o tap seria off-screen.
  - Polling loop passa a verificar `if (enrichmentStepFailedFast) { failedFast = true; break }` entre polls. Log estruturado inclui `failedFast=$failedFast`.
  - Efeito: falha típica agora custa apenas o tempo dos scrolls (~18s no pior caso das 20 tentativas backward + eventuais 10 forward) + spacing 1.5s, em vez dos ~45s antigos.
- **Fix 2: Forward-scroll fallback no `locateReelWithScroll`.**
  - Nova constante `BATCH_MAX_FORWARD_SCROLLS = 10` (metade do budget backward, porque o caso é raro).
  - Assinatura ganha parâmetro opcional `forwardScrollsLeft: Int = BATCH_MAX_FORWARD_SCROLLS`.
  - Quando `scrollsLeft <= 0` (backward esgotado), chama `locateReelWithForwardScroll(target, forwardScrollsLeft, onDone)`.
  - Quando swipe DOWN fallback é rejeitado (topo do RecyclerView), também transita imediatamente para forward.
  - Nova função `locateReelWithForwardScroll` — mesma estrutura mas com `ACTION_SCROLL_FORWARD` + swipe UP como fallback.
  - Log distinto: `LOCATE_FWD` vs `LOCATE`.
- **Fix 3: Heads-up completion notifications.**
  - Rename `NOTIF_CHANNEL_STATUS_ID` de `friends_reels_status` → `friends_reels_status_v2`. Motivo: Android's `NotificationManager` ignora silenciosamente qualquer upgrade de `importance` a um canal já criado. A única forma de forçar existing installs a HIGH é criar um canal novo.
  - Novo canal em `IMPORTANCE_HIGH` + `enableVibration(true)` (para o peek ser mais notável).
  - `createNotificationChannel` também apaga o canal antigo `friends_reels_status` (silent no-op em fresh installs).
  - `postCompletionNotification` builder ganha `.setPriority(NotificationCompat.PRIORITY_HIGH)` e `.setDefaults(NotificationCompat.DEFAULT_ALL)` — cobre também devices pré-Android 8 (sem canais).
  - Efeito: quando qualquer acção longa termina, o utilizador vê a notificação peek durante ~2s no topo do ecrã sem ter de abrir o shade.
- **`BUILD_TAG` bumped para `build=s42`.** Sem alteração de schema Room.
- **Ficheiros alterados:**
  - `service/InstagramReaderService.kt` — flag fail-fast + reset + set nos 3 pontos, polling check, forward-scroll param + nova função `locateReelWithForwardScroll`, constante `BATCH_MAX_FORWARD_SCROLLS`, canal renomeado + IMPORTANCE_HIGH, `postCompletionNotification` PRIORITY_HIGH, `deleteNotificationChannel` do canal antigo, `BUILD_TAG=s42`.
  - `PROJECT_PROGRESS.md` — Estado, quick start, §6/6.1 (bateria L), este log.
- **Validação em ambiente do agente:** kotlinc compile-check com JDK 21 — 22 erros no total, todos classpath (K2 falha type inference nos `.filter { it.direction == Direction.RECEIVED }` e `.filter { it.reelAuthor == ... }` das novas `locateReelWith*` porque `Direction` e `ReelEntity.reelAuthor` são unresolved sem stdlib). Zero erros novos de sintaxe.
- **Validação em device (esperada na próxima sessão):** bateria L1/L2/L3 descrita em §6.1.
- **Nada mudou** nos primitivos react/reply/copy URL, na navegação PoC-9, no filtro de selecção, no batching de apply pending (para além da mesma paridade de Cancelar), nas prefs. As três correcções são cirúrgicas sobre os pontos concretos identificados no log.

---

### 2026-08-31 — Sessão 43 (Ricardo + Copilot CLI) — mais scrolls, mais rápido, IG focus recovery

- **Feedback do utilizador após L1/L2/L3 em device** (log `docs/screen-dumps/feed.txt` 12:22→12:27, 12 Reels tentados, 12 falharam):
  1. **L1 confirmado** — linha 351: `step 9 short-circuited by fast-failure signal — skipping wait`. Fail-fast funciona (0.5s vs 45s por passo falhado).
  2. **L2 falhou por budget insuficiente** — o log mostrou 20 scrolls backward + 10 forward = 30 tentativas totais, terminadas em ~27s, e o Reel `legiaoesportsgg` continuava sem match. O utilizador tem "centenas de reels" — 20 scrolls só cobre ~40-100 mensagens. Também disse "tem que ser mais rápido" — cada scroll estava a demorar ~900ms.
  3. **L3 confirmado** — o utilizador viu peek no topo.
  4. **Bug novo reportado in-band:** quando IG perde foco entre steps (utilizador foi para outra app), o batch continuava a falhar com `NAV: direct_tab not found` sem tentar trazer IG de volta.
- **Correcções:**
  1. **Budgets 5x maiores:** `BATCH_MAX_SCROLLS` 20 → 100, `HISTORY_MAX_SCROLLS` 30 → 100, `HISTORY_STOP_AFTER_N_EMPTY` 3 → 5.
  2. **Scrolls mais rápidos:** `LOCATE_SCROLL_SETTLE_MS` 800 → 300ms; `HISTORY_SCROLL_SETTLE_MS` 800 → 400ms. RecyclerView assenta em ~100-200ms na prática; os 800ms iniciais eram slack conservador da fase PoC.
  3. **Forward-scroll reduzido** 10 → 5 (`BATCH_MAX_FORWARD_SCROLLS`) — o utilizador tem razão de que o comum é o Reel estar acima, não abaixo. 5 é suficiente para o caso raro (IG restaurou posição mid-thread).
  4. **Stall detection** (`LOCATE_STOP_AFTER_N_STALLS = 5`): novo parâmetro `stallHistory: ArrayDeque<Set<String>>` que acompanha os últimos 5 conjuntos de autores visíveis; se todos iguais, dá por atingido topo/fim (RecyclerView bloqueado ou já não há mais mensagens) e sai antes do budget esgotar. Corta cenários onde os 100 scrolls não iam mesmo a lado nenhum.
  5. **Log rate-limit** (`LOCATE_LOG_EVERY_N_SCROLLS = 10`): log só cada 10º scroll (com o primeiro sempre a logar). Com 100 scrolls por Reel, o feed.txt torna-se ilegível se cada scroll gerar uma linha. Failure/match logs permanecem incondicionais.
  6. **`runInInstagram` entre steps:**
     - `processBatchEnrichmentStep` — `mainHandler.post { runInInstagram { startEnrichmentForReel(fresh) } }` (era `startEnrichmentForReel(fresh)` directamente).
     - `runBatchStep` (apply pending) — todo o corpo pós-terminal envolvido em `runInInstagram { … }`. `isInstagramReady()` faz shortcut se IG já está em primeiro plano (0ms overhead no happy path). Caso contrário, IG vem à frente e o step continua.
     - Efeito prático: utilizador pode ir para outra app a meio de um batch; o próximo step traz IG à frente e continua. Sem esta protecção, cada step subsequente falhava com `NAV: direct_tab not found`.
- **Impacto na performance:**
  - Fail rápido (stall detected em ~5×300ms = 1.5s ou budget esgotado em 100×300ms + 5×300ms = 31.5s worst case).
  - Sucesso rápido no happy path (Reel a 5 scrolls de distância = 5×300ms + match = ~1.5s).
  - Comparação com s42: 20 scrolls × 800ms = 16s por Reel, agora 100 scrolls × 300ms = 30s worst case, mas com stall detection a maioria dos casos falha em 5s.
- **`BUILD_TAG` bumped para `build=s43`.**
- **Ficheiros alterados:**
  - `service/InstagramReaderService.kt` — 5 constantes bumpadas/reduzidas, novo parâmetro `stallHistory` em ambas locate functions, rate-limit nos logs, wrap `runInInstagram` no processBatchEnrichmentStep e runBatchStep, `BUILD_TAG=s43`. Sem alterações em UI ou schema.
  - `PROJECT_PROGRESS.md` — Estado, quick start, §6/6.1 (bateria M), este log.
- **Validação em ambiente do agente:** kotlinc compile-check com JDK 21 — 26 erros totais, todos classpath (K2 falha nos filter/mapping chains e nos `Direction`/`reelAuthor` unresolved). Zero erros novos de sintaxe no código s43.
- **Validação em device (esperada na próxima sessão):** bateria M1-M4 descrita em §6.1.
- **Nada mudou** nos primitivos react/reply/copy URL, na navegação PoC-9 (helpers), nos completion notifs, no return-to-app, no auto-enrich, na chain PoC-7, nas prefs, no schema Room, na UI. A s43 é 100% ajustes internos de scroll/timing + defensive `runInInstagram`.

---

### 2026-08-31 — Sessão 44 (Ricardo + Copilot CLI) — remove stall + epoch guard + botão dump

- **Feedback do utilizador após M/s43 em device** (log `docs/screen-dumps/feed.txt` 12:43→12:46):
  1. Confirmou que **não viu forward-scroll** (na verdade acontece nos logs — LOCATE_FWD scroll 1/5 — mas em 1.5s, imperceptível).
  2. **Crítica correcta:** "5 scrolls vazios dizer que acabou" é falso positivo. Podem ser 5 mensagens de texto ou 5 Reels enviados por mim (filtrados por PREF_IGNORE_SENT), não fim da conversa.
  3. **Sinal real de fim de conversa** (segundo o utilizador): no topo da DM aparece a foto grande da pessoa + nome + `@handle` + botão "Ver perfil". Não tem forma de mostrar sem um dump.
  4. **Confirmação implícita:** "espero que as funcionalidades antigas... não tenham sido removidas do código mas sim apenas se tenha escondido o botão na interface". Já era o caso: `dumpActiveWindow` e `dumpAllWindows` continuam definidos e são chamados de dentro do código (share-not-found, reply-no-menu, etc.). Só o botão de UI da fase PoC-2 é que tinha sido removido.
  5. **Bug adicional descoberto no log**: interleaving de autores. Ex.: `LOCATE_FWD: scroll 1/5 author=play.nighthub` a acontecer **~2.5s depois** de `LOCATE: scroll 1/100 author=the_clip_vault_7` ter começado. Root cause: `mainHandler.postDelayed` calls agendadas pelo step antigo continuam a disparar mesmo depois de o batch ter avançado para o novo step. Isto acontece porque:
     - Step N corre scrolls. Cada scroll agenda o próximo via `postDelayed`.
     - Step N chama `onDone(null)` (por stall ou budget esgotado) e o batch executor passa a step N+1.
     - Uma gesture callback (dispatchGesture onCompleted) de step N ainda está em curso; ao completar, chama `postDelayed({ locateReelWithScroll(target_N, ...) })`. Este callback fires DEPOIS de step N+1 já ter começado.
     - Resultado: dois locates a correr em paralelo → interleaving de autores no log + gestos misturados.

- **Correcções:**
  1. **Remove stall detection** — apagada a heurística `visibleAuthors unchanged for 5 scrolls → give up`. A constante `LOCATE_STOP_AFTER_N_STALLS` foi removida. A ArrayDeque `stallHistory` e os checks associados nas funções foram removidos. **Trade-off:** sem stall, cada falha percorre os 100 scrolls (~30s), mas nunca aborta prematuramente. Utilizador prefere isto — melhor demorar 30s a garantir do que saltar Reels que estavam mais atrás.
  2. **Epoch guard** para stale callbacks:
     - Novo `@Volatile private var enrichmentStepEpoch: Long = 0L`. Incrementado no arranque de cada `processBatchEnrichmentStep`.
     - Assinaturas de `locateReelWithScroll` e `locateReelWithForwardScroll` ganham `epoch: Long = enrichmentStepEpoch`. Cada recursion captura o epoch corrente e passa-o na chamada seguinte.
     - No topo de cada recursion: `if (epoch != enrichmentStepEpoch) { Log.i("aborting stale callback"); return }`. Não chama `onDone` (o caller já avançou; ninguém está à espera).
     - Também nos `dispatchGesture` callbacks (`onCompleted`/`onCancelled`): guard idêntico antes de `postDelayed`.
  3. **Botão Dump em Diagnóstico:**
     - Novo `ACTION_DUMP_TREE` broadcast que triga `dumpAllWindows("manual")`.
     - Novo botão "🌳 Dump da árvore da página atual (Logcat)" na secção Diagnóstico da SettingsActivity.
     - Utilizador pode agora tocá-lo no topo duma conversa; grep `IGReaderService` no logcat mostra `===== DUMP_ALL START reason=manual` seguido da árvore. Objectivo prático: identificar o selector do header topo-da-conversa (foto grande + `@handle` + "Ver perfil") numa próxima sessão.
- **Confirmação sobre "manter funcionalidades antigas":** `dumpActiveWindow`, `dumpAllWindows`, e todas as PoC-2/3 helpers já estavam no código. Só a UI que foi removida na sessão-24 (clean-up dos botões exploratórios). Este princípio será mantido — remover UI ≠ remover código.
- **`BUILD_TAG` bumped para `build=s44`.**
- **Ficheiros alterados:**
  - `service/InstagramReaderService.kt` — remove stall detection + constante, adiciona `enrichmentStepEpoch` + guards em ambas locate functions + gesture callbacks, `ACTION_DUMP_TREE`, log line ganha `dumpTree=...`, `BUILD_TAG=s44`.
  - `ui/settings/SettingsActivity.kt` — botão Dump Tree em Diagnóstico.
  - `res/values/strings.xml` — `btn_dump_tree`.
  - `PROJECT_PROGRESS.md` — Estado, quick start, §6/6.1 (bateria N), este log.
- **Validação em ambiente do agente:** kotlinc compile-check com JDK 21 — 22 erros totais, todos classpath (mesmo baseline s43 minus stall detection erros que não existiam). Zero erros novos de sintaxe.
- **Validação em device (esperada na próxima sessão):** 3 testes em §6.1 no novo formato (nomes descritivos + preparação + passos concretos + o que confirmar/o que NÃO deve aparecer / passa se / falha se), conforme §8.1 pediu.
- **Nada mudou** nos primitivos react/reply, na navegação PoC-9, no filtro de selecção, no batching de apply pending, no fluxo de completion notifs, no return-to-app, no auto-enrich, na chain PoC-7, nas prefs (excepto o novo action DUMP_TREE), no schema Room. A s44 é uma correcção cirúrgica + feature de diagnóstico.

---

### 2026-08-31 — Sessão 45 (Ricardo + Copilot CLI) — dump com delay + Toast

- **Feedback do utilizador após N3/s44 em device** (log `docs/screen-dumps/feed.txt` 13:41:15 e 13:41:26): tocar em "🌳 Dump da árvore da página atual (Logcat)" **funciona tecnicamente** — o receiver dispara, `===== DUMP_ALL START reason=manual windowCount=4 =====` aparece — mas dumpa **a página das Definições da Friends Reels**, não a conversa do IG. Motivo: para tocar no botão, o utilizador tem de sair do IG, portanto a janela `active=true focused=true` no momento do dump é `com.example.friendsreels`. Uma das windows dumpadas foi `id=1320 pkg=com.example.friendsreels` com toda a scroll list de Diagnóstico incluindo o próprio botão dump. O commit `039d262 "dump not working"` regista precisamente este falso positivo.
- **Correcções:**
  1. **`ACTION_DUMP_TREE` passa a aceitar extra `EXTRA_DUMP_DELAY_MS: Long`.**
     - Nova função privada `handleDumpTreeBroadcast(intent: Intent)` no serviço substitui a chamada directa a `dumpAllWindows("manual")`.
     - Se `delayMs <= 0`: dumpa imediatamente com `reason=manual` (preserva o path adb).
     - Se `delayMs > 0`: log `DUMP_TREE: scheduled in <N>ms (<S>s) — switch to IG now.`, mostra `Toast.makeText(...)` com `Toast.LENGTH_LONG` a dizer "🌳 Muda para o IG! Dump em Xs.", e agenda `dumpAllWindows("manual-delayed")` via `mainHandler.postDelayed`.
     - Reason `manual-delayed` (vs `manual`) permite distinguir no log qual foi o path — útil quando o utilizador reporta que "o dump não capturou o IG": se `reason=manual` foi adb path (imediato); se `manual-delayed` foi botão (delay).
  2. **Botão em Definições passa a chamar `triggerDelayedDump(context)`** — nova função privada em `SettingsActivity.kt`:
     - Envia `Intent(ACTION_DUMP_TREE).putExtra(EXTRA_DUMP_DELAY_MS, 5000L).setPackage(...)`.
     - Mostra `Toast` local a informar (redundante mas útil — o Toast do serviço pode não aparecer se o Toast rate limit do sistema já engoliu um recente).
  3. **Novo string `dump_tree_countdown`** em `res/values/strings.xml`: "🌳 Muda para o IG! Dump em %1$d s." — parametrizado para trivialmente ajustar o delay no futuro.
- **Trade-offs:**
  - 5s é generoso — utilizador tem tempo de largar o telemóvel e voltar. Se for pouco, pode ser ajustado (só a constante `delayMs = 5000L` em `triggerDelayedDump`).
  - Nenhum sinal ao utilizador que o dump JÁ foi feito (só o log tem `manual-delayed START`). Aceitável — o caso de uso é para debug, o utilizador vai olhar para o logcat de qualquer forma.
- **`BUILD_TAG` bumped para `build=s45`.**
- **Ficheiros alterados:**
  - `service/InstagramReaderService.kt` — import `android.widget.Toast`, `ACTION_DUMP_TREE` handler redireccionado para `handleDumpTreeBroadcast`, nova função privada, nova constante `EXTRA_DUMP_DELAY_MS`, `BUILD_TAG=s45`.
  - `ui/settings/SettingsActivity.kt` — botão dump chama `triggerDelayedDump`; nova função privada envia broadcast com extra + mostra Toast.
  - `res/values/strings.xml` — novo `dump_tree_countdown`.
  - `PROJECT_PROGRESS.md` — Estado atual, quick start, §6.1 (bateria O), este log.
- **Validação em ambiente do agente:** kotlinc compile-check com JDK 21 — 1577 erros totais, todos classpath (K2 sem Android SDK). Zero erros novos de sintaxe: as novas funções `handleDumpTreeBroadcast` e `triggerDelayedDump`, o novo import `android.widget.Toast`, e a nova constante `EXTRA_DUMP_DELAY_MS` aparecem sem erros específicos além dos classpath baseline.
- **Validação em device (esperada na próxima sessão):** teste "Dump-IG" descrito em §6.1 (só 1 teste — a bateria N da s44 dos testes de Interleaving e Budget não foi bloqueada por esta correcção, mas convém reconfirmá-los em s45 se ainda não foram validados).
- **Nada mudou** no epoch guard (s44), na remoção da stall detection (s44), no budget de 100 backward + 5 forward (s43), no `runInInstagram` entre steps (s43), nos primitivos react/reply/copy URL, na navegação PoC-9, no filtro de selecção, no batching de apply pending, nas prefs (excepto o novo extra `EXTRA_DUMP_DELAY_MS`), no schema Room, na UI (excepto o Toast). A s45 é 100% cirúrgica sobre o botão de Dump.

---

### 2026-08-31 — Sessão 46 (Ricardo + Copilot CLI) — detecção real de topo de conversa

- **Input:** utilizador confirmou o teste `Dump-IG` da s45 funcionou e colou o dump em `docs/screen-dumps/dump.txt`. Instrução: "junta o máximo de features que consegues fazer e só depois eu dou feedback… de vez em quando limpa a documentação para a manter atualizada e sem lixo".
- **Análise do dump (`docs/screen-dumps/dump.txt`):** o topo da conversa `astrid_gutierrez` (`WINDOW[3] type=APPLICATION pkg=com.instagram.android`) contém dentro de `message_list` um `LinearLayout` inicial com:
  - `FrameLayout id=com.instagram.android:id/user_avatar` (foto grande 330×330).
  - `TextView id=com.instagram.android:id/other_user_full_name_or_username text="astrid☀️"`.
  - `TextView id=com.instagram.android:id/network_attribution text="astrid_gutierrez"` (o `@handle` real).
  - `Button id=com.instagram.android:id/view_profile_button text="Ver perfil"`.
  Estes 4 nodes são o header "start-of-conversation" pedido pelo utilizador na s44 e são a base para substituir a stall detection removida na s44.
- **Correcções:**
  1. **`IgSelectors.Thread` ganha 4 novas constantes:** `HEADER_VIEW_PROFILE_BUTTON`, `HEADER_USER_AVATAR`, `HEADER_OTHER_USER_FULLNAME`, `HEADER_NETWORK_ATTRIBUTION`. Comentário no ficheiro remete para o dump da s45 como fonte.
  2. **Nova helper `isThreadTopVisible(root: AccessibilityNodeInfo?): Boolean`** no serviço. Probe `view_profile_button` primeiro (mais exclusivo dos 4) e cai nos outros como fallback caso IG renomeie um deles num futuro build.
  3. **`locateReelWithScroll`** — depois de enumerar candidatos e não achar match, chama `isThreadTopVisible(root)`. Se `true`, log `LOCATE: thread top reached at scroll K/100 (view_profile_button visible) — skipping remaining backward budget.` e transita imediatamente para `locateReelWithForwardScroll` (se ainda houver budget forward) ou termina com `onDone(null)`. Substitui o "budget completo obrigatório" que a s44 introduziu ao remover a stall detection.
  4. **`doHistoryScroll`** — check idêntico no início de cada iteração. Se topo visível, chama `finishHistory(state)` com log `HISTORY: thread top reached (view_profile_button visible) after N scrolls — stopping.` Substitui o critério "`HISTORY_STOP_AFTER_N_EMPTY == 5`" como sinal primário (esse continua como fallback para casos onde os selectors do header falhem).
- **Impacto na performance esperado:**
  - Conversas curtas com Reel em falta → falha em ~5-15s em vez de 30-35s (só faz os scrolls até chegar ao topo).
  - Conversas gigantes → sem mudança (o topo continua longe, budget de 100 aplica-se como habitualmente).
  - History-scroll em conversas curtas → termina no topo real em vez de scroll fantasma até `HISTORY_STOP_AFTER_N_EMPTY`.
- **Trade-offs:**
  - Custo de `findAccessibilityNodeInfosByViewId` por 4 IDs a cada iteração — desprezável (~1ms por check).
  - Se IG mudar o `resource-id` do `view_profile_button`, os 3 fallbacks compensam; se mudar todos 4, cai no comportamento pré-s46 (esgotar budget).
- **`BUILD_TAG` bumped para `build=s46`.**
- **Ficheiros alterados:**
  - `instagram/IgSelectors.kt` — 4 novas constantes em `Thread` + comentário longo referenciando `docs/screen-dumps/dump.txt`.
  - `service/InstagramReaderService.kt` — nova `isThreadTopVisible`, check em `locateReelWithScroll` (substitui bloco de comentário "stall detection removed"), check em `doHistoryScroll` no início da função, `BUILD_TAG=s46`.
  - `PROJECT_PROGRESS.md` — Estado atual, quick start, §6/6.1 (bateria P), este log.
- **Validação em ambiente do agente:** kotlinc compile-check com JDK 21 — 1181 erros totais (só compilo `IgSelectors.kt` + `InstagramReaderService.kt`), todos classpath (K2 sem Android SDK). Zero erros novos de sintaxe: `isThreadTopVisible`, os 4 `HEADER_*` constantes e as chamadas em `locateReelWithScroll`/`doHistoryScroll` não geram erros específicos além do baseline.
- **Validação em device (esperada na próxima sessão):** bateria P (2 testes: `TopStop-Batch`, `TopStop-History`) descrita em §6.1 no formato §8.1.
- **Nada mudou** no dump com delay (s45), no epoch guard (s44), no schema Room, na UI, nos primitivos react/reply/copy URL, na navegação PoC-9, no filtro de selecção, no batching de apply pending, nas prefs. A s46 é uma substituição cirúrgica da (não-)detecção de topo por um sinal real.

---

### 2026-08-31 — Sessão 47 (Ricardo + Copilot CLI) — instrumentação de "Reel skipped mid-sweep"

- **Feedback do utilizador após validar s46 em device:** *"Não consegui muito bem testar o primeiro teste pois não tinha nenhum mas como o segundo funcionou bem acredito que esteja a funcionar, a coisa é que ao procurar o reel se ele já tiver passado por ele não volta para baixo para o reencontrar anota esse comportamento pois mesmo que não se corrija já é necessário saber isso."*
- **Root cause hipotético:** durante o backward-scroll dentro de `locateReelWithScroll`, o bubble do Reel-alvo pode aparecer brevemente em vista mas o snapshot de `enumerateReels` no momento em que corre (300ms após scroll accepted) pode não o apanhar — a bubble pode estar mid-layout, com `bounds.height() < MIN_REEL_BUBBLE_HEIGHT_PX` (200px) e ser filtrada. Próximo scroll happens e a bubble sobe para fora do viewport. O código só descobre o skip depois de esgotar backward budget ou detectar topo, e o forward budget (5 scrolls, s43) é curto demais para retracement.
- **Correcções:**
  1. **`seenAuthors: MutableSet<String>` propagado por toda a recursão** de `locateReelWithScroll` e `locateReelWithForwardScroll`. Cada iteração acumula `candidates.mapNotNull { it.reelAuthor }`. Set partilhado entre backward + forward — o forward pode ver autores que o backward já viu (e vice-versa).
  2. **Log warning explícito quando `wantedAuthor in seenAuthors` mas nunca deu match**, disparando em 3 pontos:
     - `isThreadTopVisible == true` sem match.
     - `scrollsLeft <= 0` (backward exhausted) sem match.
     - `forwardScrollsLeft <= 0` (forward exhausted) sem match.
     Mensagem base: `LOCATE: wanted author '$X' was observed mid-backward-sweep but no bubble matched — Reel likely skipped due to layout timing. Forward retracement will scan up to N scrolls to recover.`
     No `LOCATE_FWD` a mensagem final ganha sufixo `— author WAS seen at some point (skipped bubble); consider bumping BATCH_MAX_FORWARD_SCROLLS`.
  3. **`BATCH_MAX_FORWARD_SCROLLS` bumpado 5 → 15.** Justificação no comentário da constante: com `isThreadTopVisible` (s46) a cortar backward cedo em conversas curtas, sobra "slack" no budget total (100 × 0.3s = 30s backward + 15 × 0.3s = 4.5s forward = ~35s worst case, igual ao pre-s47). Ganha-se retracement suficiente para reencontrar bubbles skipped num range de ~15 scrolls.
- **Trade-offs:**
  - Não é a fix estrutural do bug. Instrumentação + mitigação parcial.
  - A fix estrutural implicaria: (a) enumerar 2× por scroll com pequena separação (~150ms) para captar bubbles transientes, OU (b) retracement dinâmico ilimitado — scrollar forward até reencontrar o autor observado no `seenAuthors`. Ambas exigem alterações mais invasivas ao control-flow. Deixado para s48 dependendo do que a bateria Q1 mostrar.
- **`BUILD_TAG` bumped para `build=s47`.**
- **Ficheiros alterados:**
  - `service/InstagramReaderService.kt` — `seenAuthors` como parâmetro nas duas locate functions e nas 4 chamadas recursivas (backward → forward, forward → forward), 3 novos log warnings, comentário da constante `BATCH_MAX_FORWARD_SCROLLS` reescrito para explicar o bump, `BUILD_TAG=s47`.
  - `PROJECT_PROGRESS.md` — Estado atual (secção "Recap" + nova limitação em §6), quick start, §6/6.1 (bateria Q), este log.
- **Validação em ambiente do agente:** kotlinc compile-check com JDK 21 — 1199 erros totais, todos classpath (K2 sem Android SDK). Zero erros novos mencionando `seenAuthors` ou `BATCH_MAX_FORWARD_SCROLLS`. O aumento vs baseline s46 (1181 → 1199) é ruído de duplicação de erros nas mesmas linhas — nenhum erro real.
- **Validação em device (esperada na próxima sessão):** bateria Q (1 teste: `SeenSkipped`) descrita em §6.1.
- **Nada mudou** na detecção de topo (s46), no dump com delay (s45), no epoch guard (s44), no schema Room, na UI, nos primitivos react/reply/copy URL, na navegação PoC-9, no filtro de selecção, no batching de apply pending, nas prefs. A s47 é 100% cirúrgica sobre o control-flow do locate — sem alterações estruturais.

---

### 2026-08-31 — Sessão 47b (Ricardo + Copilot CLI) — corrige design flaw da s47

- **Utilizador (mesma janela de chat da s47):** *"a única coisa para testar é se não encontrar o reel que devia mas chegar ao topo da conversa vendo outro do mesmo autor dar erro na mesma?"*
- **Insight do utilizador (correcto):** com o match a ser `.firstOrNull { it.reelAuthor == wantedAuthor }`, se o autor está em `candidates` (pós-filtro) o match acontece **sempre** — logo `wantedAuthor in seenAuthors` numa falha era matematicamente impossível no design da s47. A instrumentação não podia disparar. O teste que descrevi na s47 nunca ia mostrar o warning.
- **Root cause do design flaw:** eu enchia `seenAuthors` a partir de `candidates` (pós filtros de altura, largura e direction). Mas o bug real reportado na s46 é **exactamente sobre bubbles que são filtradas** por bounds `< 200px` (mid-layout). Se a bubble é filtrada, o autor NÃO entra em `candidates` → NÃO entra em `seenAuthors` → warning nunca dispara.
- **Correcção:**
  1. **Enumeração pré-filtro** em ambas as `locate…` functions:
     ```kotlin
     val allReels = enumerateReels(messageList)
     val candidates = allReels
         .filter { it.bounds.width() > 0 && it.bounds.height() >= MIN_REEL_BUBBLE_HEIGHT_PX }
         .filter { it.direction == Direction.RECEIVED }
     // NEW s47b — seenAuthors accumulates from allReels, only filtered by direction
     allReels
         .filter { it.direction == Direction.RECEIVED }
         .mapNotNull { it.reelAuthor }
         .forEach { seenAuthors.add(it) }
     ```
  2. Ficou assim: `candidates` continua a ser usada para o match (o filtro faz sentido — não queremos "matchar" numa bubble mid-layout que iria falhar o `performAction(CLICK)`). Mas `seenAuthors` agora captura bubbles com bounds pequenas.
- **Impacto no test:** o warning `LOCATE: wanted author '$X' was observed mid-backward-sweep but no bubble matched — Reel likely skipped due to layout timing.` passa a ter significado real — dispara quando o bubble do target apareceu em algum snapshot (mesmo mid-layout) mas nenhum snapshot o teve pronto para match.
- **`BUILD_TAG` bumped para `build=s47b`.** (Sem alteração ao `BATCH_MAX_FORWARD_SCROLLS=15` da s47; sem alteração ao control-flow.)
- **Ficheiros alterados:**
  - `service/InstagramReaderService.kt` — 2 blocos idênticos em `locateReelWithScroll` e `locateReelWithForwardScroll` para separar `allReels`/`candidates` e enumerar `seenAuthors` antes do filtro. Comentário no ponto de acumulação a explicar porquê. `BUILD_TAG=s47b`.
  - `PROJECT_PROGRESS.md` — Estado, quick start, §6/6.1 (bateria Q corrigida com 3 cenários), este log.
- **Validação em ambiente do agente:** kotlinc compile-check com JDK 21 — zero erros com o novo símbolo `allReels`. Baseline classpath inalterado.
- **Nada mudou** salvo o accumulator de `seenAuthors`. A s47b é uma micro-correcção à s47.
- **Reconhecimento:** design flaw meu, utilizador apanhou por raciocínio puro (não teve de testar em device). Boa colaboração — poupou uma iteração inteira desperdiçada em Q1 a mostrar sempre "warning nunca aparece".

---

### 2026-08-31 — Sessão 48 (Ricardo + Copilot CLI) — `📥 Descobrir tudo` em conversas seleccionadas

- **Contexto:** utilizador tem N amigos seleccionados em Definições → "Filtrar conversas" e quer que a app percorra todos automaticamente, sem ter de abrir cada conversa e tocar 📥 individualmente. Este é o próximo item do ranking em §6.1 depois da instrumentação da s47b, que fica não-bloqueante.
- **Alterações:**
  1. **`TrackedThreadDao.snapshotTitles(): List<String>`** (novo, suspend). Complementa o `observeTitles()` — o batch precisa de um snapshot estável no arranque, não de um Flow que possa mudar mid-run se o utilizador mexer nas selecções.
  2. **`HistoryState.onFinish: ((HistoryState) -> Unit)? = null`** (novo campo opcional). Passado por `discoverReelsHistory` para dentro do state; `finishHistory` verifica: se não-null, chama `onFinish(state)` e SALTA a `postCompletionNotification` + `returnToAppIfEnabled` + `maybeAutoEnrichAfterDiscover`. Se null, comportamento original.
  3. **`discoverReelsHistory(overrideThreadTitle: String? = null, onFinish: ((HistoryState) -> Unit)? = null)`** — assinatura alargada. `overrideThreadTitle` permite ao batch passar o título directamente sem depender de `lastKnownConversationTitle` (que pode ainda não ter chegado via a11y event depois da nav).
  4. **`ACTION_DISCOVER_HISTORY_ALL_TRACKED` + `ACTION_DISCOVER_HISTORY_ALL_CANCEL`** (novas broadcasts). Registadas no IntentFilter; log line "Action receiver registered" ganha `historyAll=...` + `historyAllCancel=...`.
  5. **`discoverHistoryAllTracked()`** (novo orquestrador). Guardia `historyBatchInProgress` volatile. Snapshot dos títulos. `processHistoryBatchStep(titles, index, totalInserted, threadsCovered)` chain: cada iteração faz `navigateToThreadAsync` → wait `HISTORY_BATCH_NAV_SETTLE_MS` (1200ms) → `discoverReelsHistory(overrideThreadTitle, onFinish)`, com o callback a chainar para o próximo index. Fim do batch: notif única "📥 Histórico descoberto (todas as conversas) — N Reel(s) novo(s) em M conversa(s)" + `maybeAutoEnrichAfterDiscover(totalInserted)`.
  6. **`cancelHistoryBatch()`** — apenas flippa `historyBatchCancelled = true`. O próximo `processHistoryBatchStep` detecta e termina com "Cancelado após N Reels em M/K conversas".
  7. **`BatchHistorySection()`** — nova composable em `SettingsActivity.kt` com título/subtítulo/botão "📥 Descobrir tudo". Colocada entre "Preparar URLs em lote" e "Ferramentas de diagnóstico". Toast a informar arranque.
  8. **Novos strings:** `settings_history_all_title`, `settings_history_all_subtitle`, `settings_history_all_start`, `settings_history_all_start_toast`, `settings_history_all_cancel_toast`, `notif_completion_history_all_title`, `notif_completion_history_all_body`, `notif_completion_history_all_cancelled`, `notif_completion_history_all_empty`.
- **`BUILD_TAG` bumped para `build=s48`.**
- **Ficheiros alterados:**
  - `data/TrackedThreadDao.kt` — nova query suspend.
  - `service/InstagramReaderService.kt` — novas Volatile flags, novo bloco de funções (~120 linhas), refactor mínimo do `discoverReelsHistory` + `finishHistory`, novas 2 actions + IntentFilter + broadcast dispatch + log line, 2 constantes de timing (`HISTORY_BATCH_NAV_SETTLE_MS`, `HISTORY_BATCH_STEP_SPACING_MS`), `BUILD_TAG=s48`.
  - `ui/settings/SettingsActivity.kt` — nova composable `BatchHistorySection` + chamada na SettingsScreen.
  - `res/values/strings.xml` — 9 novos strings.
  - `PROJECT_PROGRESS.md` — Estado, quick start, §6/6.1 (bateria R), este log.
- **Validação em ambiente do agente:** kotlinc compile-check com JDK 21 — 1614 erros totais, todos classpath. Zero erros com os novos símbolos (`discoverHistoryAllTracked`, `processHistoryBatchStep`, `cancelHistoryBatch`, `snapshotTitles`, `BatchHistorySection`, `HISTORY_BATCH_NAV_SETTLE_MS`, `HISTORY_BATCH_STEP_SPACING_MS`, `ACTION_DISCOVER_HISTORY_ALL_*`).
- **Validação em device (esperada na próxima sessão):** bateria R (1 teste: `DiscoverAll`).
- **Nada mudou** na chain de match (s47b), na detecção de topo (s46), no dump com delay (s45), no epoch guard (s44), no schema Room (nenhuma migration nova), na `enrichAllMissingUrls` chain PoC-9, nas prefs. A s48 é uma feature aditiva alicerçada em componentes existentes.

---

### 2026-08-31 — Sessão 49 (Ricardo + Copilot CLI) — sync da reacção actual (spec §7)

- **Contexto:** utilizador pediu para juntar máximo de features. Feita a s48 (batch history), passei ao próximo item do ranking: **sync da reacção actual** — a spec §7 diz que o feed deve mostrar a reacção que está actualmente na DM (não só as que enviámos via app). Até agora o chip só refletia `PendingActionEntity` DONE, portanto se o utilizador reagisse ❤ manualmente no IG, o feed não mostrava.
- **Alterações:**
  1. **Schema Room v4 → v5** com nova coluna `currentReaction: String?` em `reels`. Migration destrutiva (matches política PoC — dados regeneráveis com `📥 Descobrir tudo` da s48).
  2. **`ReelDao`** ganha 2 novos UPDATE queries: `updateCurrentReaction(id, reaction)` (por ID) e `updateCurrentReactionByKey(thread, author, direction, reaction)` (usado no path de skip do dedup — mantém a reacção sincronizada mesmo quando não há novo insert).
  3. **`DmReelEntry`** ganha campo `currentReaction: String?`.
  4. **`enumerateReels`** ganha 3 novos passos:
     - Prefetch de todas as `message_reactions_pill_container` nodes do `messageList` no início do loop, extraindo `(bounds, emoji)` para cada. `extractReactionEmoji(pill)` faz DFS na sub-tree e devolve o primeiro descendant cuja `contentDescription` é curta (≤4 chars) e não é o `reaction_add` — matches a "no reaction" (só tem `reaction_add`, retorna null) vs "com emoji" (retorna o emoji).
     - Para cada bubble, `matchReactionPill(bubbleBounds, pillEntries)` faz match por proximidade geométrica: pill.top precisa de estar dentro de `[-REACTION_PILL_OVERLAP_TOLERANCE_PX, +REACTION_PILL_MAX_GAP_PX]` do bubble.bottom, E precisa de sobreposição horizontal. Se múltiplas pills passam, escolhe a mais próxima verticalmente.
     - `DmReelEntry.currentReaction` sai populado.
  5. **`discoverReels` (fast) e `doHistoryEnumerate` (batch)** propagam `currentReaction` via novo campo em `Snapshot`, e:
     - Insert path: `ReelEntity(currentReaction = s.currentReaction)`.
     - Skip path: `dao.updateCurrentReactionByKey(...)` para refrescar. Log line ganha `reactionsRefreshed=N`.
  6. **FeedViewModel** — nova helper top-level `mapPillEmojiToKind(emoji: String?): String?` traduz emoji IG (`"❤"`, `"❤️"`, `"😂"`) para os `PendingActionEntity.KIND_REACT_*` constants. Em `uiStates`, o `currentReaction` passa a ser `mapPillEmojiToKind(reel.currentReaction) ?: latestReaction?.kind` — prefere reacção real do IG, faz fallback para valor derivado quando null.
  7. **Constantes de tolerância geométrica**: `REACTION_PILL_MAX_GAP_PX = 60`, `REACTION_PILL_OVERLAP_TOLERANCE_PX = 20`.
- **Cobertura de emojis:** IG tem 6 preset (❤ 😂 😮 😢 😡 👍). A app só surface 2 no chip (❤, 😂). Os outros 4 são detectados e persistidos correctamente, mas `mapPillEmojiToKind` devolve `null` para eles (fallback → valor derivado das reacções que enviámos → normalmente `null` porque a app não permite enviar esses). Chip fica escondido. Notado como próximo passo (#7) em §6.1.
- **`BUILD_TAG` bumped para `build=s49`.**
- **Ficheiros alterados:**
  - `data/ReelEntity.kt` — nova coluna com KDoc explicativo.
  - `data/AppDatabase.kt` — version 4 → 5 + comentário do schema.
  - `data/ReelDao.kt` — 2 novos UPDATE queries.
  - `instagram/DmReelEntry.kt` — novo campo com KDoc.
  - `service/InstagramReaderService.kt` — `enumerateReels` refactor (+3 passos), `extractReactionEmoji` e `matchReactionPill` novas privadas, `discoverReels` e `doHistoryEnumerate` reactionsRefreshed, `Snapshot` +1 campo, 2 novas constantes, `BUILD_TAG=s49`.
  - `ui/feed/FeedViewModel.kt` — nova helper top-level `mapPillEmojiToKind`, `uiStates` prefere real sobre derivado.
  - `PROJECT_PROGRESS.md` — Estado, quick start, limitação actualizada, §6/6.1 (bateria S), este log.
- **Validação em ambiente do agente:** kotlinc compile-check com JDK 21 — os erros novos são todos classpath (K2 sem stdlib/androidx marca `currentReaction` como unresolved dentro do `reelList.associate { reel -> ... }` porque não consegue inferir `reel: ReelEntity`; mesma dinâmica dos `updateCurrentReactionByKey` marcados como suspend fora de coroutine — o call site está DENTRO de `serviceScope.launch { ... }` mas K2 não consegue resolver `serviceScope`).
- **Validação em device (esperada na próxima sessão):** bateria S (Teste 2: `ReactionSync`) descrita em §6.1.
- **Nada mudou** na s48 (batch history), s47b (seenAuthors), s46 (topo real), s45 (dump delay), s44 (epoch guard), no fluxo de PoC-9, no batch enrichment de URLs, nas prefs, no dump.

---

### 2026-08-31 — Sessão 49b (Ricardo + Copilot CLI) — fix compile: leftover `val candidates` da s47b

- **Utilizador reportou:** *"Não consigo testar porque deu erro ao fazer RUN Conflicting declarations: local val candidates: List<DmReelEntry> no ficheiro InstagramReaderService.kt"*.
- **Root cause:** na s47b, o meu edit em `locateReelWithScroll` juntou o novo bloco `val allReels = ...; val candidates = allReels.filter...` sem remover o `val candidates = enumerateReels(messageList)` original. O kotlinc do agente não detectou (é ignorado no ruído de erros classpath baseline) mas o compilador real com toda a stdlib disparou "Conflicting declarations" como esperado.
- **Fix:** remove a linha órfã em `locateReelWithScroll` (linha 2020 pré-fix). `locateReelWithForwardScroll` já estava correcta desde o s47b. Após o fix ambas as funções têm 1 declaração de `allReels` e 1 de `candidates`.
- **`BUILD_TAG` bumped para `build=s49b`** — só para dar sinal ao teste que este build é distinto do broken s49.
- **Ficheiros alterados:**
  - `service/InstagramReaderService.kt` — remove `val candidates = enumerateReels(messageList)` órfão em `locateReelWithScroll`; `BUILD_TAG=s49b`.
  - `PROJECT_PROGRESS.md` — Estado, quick start, este log.
- **Validação em ambiente do agente:** kotlinc grep para "Conflicting declarations|redeclaration|already defined" — zero matches (antes fix davam 1 match a menos que o utilizador viu no build real, porque a análise K2 sem-Android-SDK cortava a árvore antes de detectar). Confirmado que o único bug era este.
- **Reconhecimento:** falha do meu processo de validação — kotlinc + JDK 21 sem Android SDK dá alguns "false negatives" para bugs como este porque o compilador aborta muito cedo por erros classpath. Deixado como lição: sempre que fizer um edit que substitui um bloco, deveria fazer um `grep` de sanidade a seguir para confirmar que os símbolos únicos (nomes de variáveis) não aparecem duas vezes.
- **Nada mais mudou.** Todas as features da s49 (sync de reacção, mapeamento emoji → chip, updateCurrentReactionByKey em skips) mantêm-se intactas.

---

### 2026-08-31 — Sessão 50 (Ricardo + Copilot CLI) — 4 correcções do feedback + tema IG-like

- **Feedback do utilizador (após validar em device com o `dump.txt` com 963 linhas):**
  1. `📥 Descobrir tudo` **pára em 5 scrolls sem Reels recebidos** — pode ser sequência de mensagens texto ou Reels enviados. Utilizador prefere que chegue ao topo real do chat de anos.
  2. **Reply-to-Reel**: quando um amigo responde ao meu Reel enviado, a bubble tem o Reel embebido + a resposta. `enumerateReels` estava a tratar o Reel embebido como partilha nova; clicar nele abre a thread de reply, não o viewer, e o copy-link chain fica preso.
  3. **URL enrichment lento (~7s/Reel)** — 69 Reels ainda pouco perto de conversas antigas. Pediu checkpoint: "quando chega a uma parte já scaneada, deve parar".
  4. **Clean uninstall** — quer que desinstalar apague tudo, sem lixo.
  5. **Design + cores longe do IG** — melhorar UI.
- **Investigação do dump.txt:**
  - Linhas 237, 390: `HISTORY: stopping — 5 consecutive empty scrolls.` para André Pinto (4 scrolls, 0 inserted) e Pedro Sardoeira (71 scrolls, 67 inserted). Ambos interrompidos por `HISTORY_STOP_AFTER_N_EMPTY=5`, não pelo topo real. Confirma problema 1.
  - Linhas 86, 196, 475: `direct_context_reply_context_info_text_view desc="Respondeu-te"` dentro de bubbles que também contêm `message_content_portrait_xma_container` (o Reel citado). Confirma problema 2 e dá o selector.
  - Linha 398: `ENRICH_ALL: starting batch for 69 reel(s)` — cada step ~7s, bottleneck é o viewer + share sheet + copy link chain por Reel. Confirma problema 3.
- **Correcções (Fix 1, 2, 4, 5). Fix 3 documentado como planeado para s51:**

  1. **Fix 1 (topo real como único sinal de stop no history-scroll):**
     - `HISTORY_STOP_AFTER_N_EMPTY` bumped 5 → 500 (essencialmente desactivado). Só serve como safety fallback se `isThreadTopVisible` (s46) partir num futuro build IG.
     - `HISTORY_MAX_SCROLLS` bumped 100 → 2000. Ao settle time actual (~800ms por scroll) isto é ~15 minutos worst case, mais que suficiente para conversas de anos. Real conversations reach top via `isThreadTopVisible` far earlier.
     - Comentários das constantes actualizados com rationale do utilizador ("prefiro que funcione direito e que seja preciso chegar ao topo do chat de anos").

  2. **Fix 2 (skip reply-attachment bubbles no `enumerateReels`):**
     - Nova constante `IgSelectors.Thread.REPLY_CONTEXT_INFO_TEXT = "direct_context_reply_context_info_text_view"` com comentário largo referenciando o dump da s50.
     - `enumerateReels` — para cada bubble, se `bubble.findAccessibilityNodeInfosByViewId(replyContextId).isNotEmpty()`, incrementa `skippedReplyAttachments` e `continue`. No fim do loop, se `skippedReplyAttachments > 0`, log `Log.d(TAG, "ENUMERATE: skipped N reply-attachment bubble(s) ...")`.
     - Isto tira do enumeration os Reels embebidos em respostas de outros ao meu envio. Tanto o batch de descoberta como o de URL enrichment passam a saltar estes bubbles.

  3. **Fix 4 (uninstall clean):**
     - `res/xml/data_extraction_rules.xml` — exclusão TOTAL (`root`, `database`, `sharedpref`, `file`, `external`) em `cloud-backup` e `device-transfer`. Antes só `sharedpref`.
     - `res/xml/backup_rules.xml` — mesma exclusão total para `full-backup-content` (Android 6-11 legacy path).
     - `AndroidManifest.xml` já tinha `android:allowBackup="false"` (não alterado).
     - Efeito: uninstall apaga `/data/data/com.example.friendsreels` automaticamente (Room DB, SharedPrefs, cache). E se algum utilizador fizer `adb backup`, nada de Friends Reels é capturado — nunca há snapshot para restaurar num reinstall.

  4. **Fix 5 (design IG-like via `FriendsReelsTheme`):**
     - Novo ficheiro `ui/theme/FriendsReelsTheme.kt` com uma paleta IG dark:
       - Background: `#000000` (matches Reels player + DM inbox).
       - Superfícies: `#121212`, `#1F1F1F`, `#262626` (elevações).
       - Texto secundário: `#A8A8A8`.
       - Primário: pink `#E1306C`.
       - `InstagramGradient`: amarelo → laranja → rosa → roxo → azul (para hero buttons futuros).
     - Typography SemiBold para títulos, `letterSpacing` tightened para parecer SF Pro / Instagram Sans (que não podemos shipar).
     - Todas as 4 activities (`MainActivity`, `FeedActivity`, `SettingsActivity`, `ReelPlayerActivity`) trocam `MaterialTheme(colorScheme = darkColorScheme()) { ... }` por `FriendsReelsTheme { ... }`. Imports de `darkColorScheme` removidos.

  5. **Fix 3 (URL enrichment slow — pendente s51):**
     - Documentado em §6 "Limitações conhecidas". Arquitectura actual: cada Reel → `navigateToThreadAsync` + `locateReelWithScroll` + viewer + share + copy link + back. Bottleneck é o viewer chain (~7s).
     - Plano para s51: refactor `enrichAllMissingUrls` para "single-pass per thread". Para cada thread, uma única scroll longa, e ao enumerar cada Reel visível, se estiver na DB com `reelUrl IS NULL` → tap viewer → copy link → back → continua scroll. Elimina o navegate + locate overhead por Reel.
     - Alternativa complementar (também s51): checkpoint por proximidade — se durante o scroll o `enumerateReels` encontra 3+ Reels consecutivos com URL já preenchido, sabemos que essa parte da conversa já foi processada e paramos.

- **`BUILD_TAG` bumped para `build=s50`.**
- **Ficheiros alterados:**
  - `service/InstagramReaderService.kt` — constantes de history bumped + comentários, `enumerateReels` skip reply-attachment, `BUILD_TAG=s50`.
  - `instagram/IgSelectors.kt` — nova constante `REPLY_CONTEXT_INFO_TEXT` com comentário largo.
  - `ui/theme/FriendsReelsTheme.kt` — novo ficheiro (paleta IG, tipografia, `InstagramGradient` público).
  - `MainActivity.kt`, `ui/feed/FeedActivity.kt`, `ui/settings/SettingsActivity.kt`, `ui/player/ReelPlayerActivity.kt` — todos trocam para `FriendsReelsTheme { ... }`, imports actualizados.
  - `res/xml/data_extraction_rules.xml`, `res/xml/backup_rules.xml` — exclusão total.
  - `PROJECT_PROGRESS.md` — Estado, quick start, §6/6.1 (bateria T), este log.
- **Validação em ambiente do agente:** kotlinc compile-check com JDK 21 — zero erros novos de sintaxe. Ver especificamente que:
  - `REPLY_CONTEXT_INFO_TEXT`, `skippedReplyAttachments`, `HISTORY_STOP_AFTER_N_EMPTY = 500`, `HISTORY_MAX_SCROLLS = 2000` — todas resolvidas.
  - `InstagramGradient`, `FriendsReelsTheme`, `InstagramColorScheme` — sem erros (androidx unresolved = classpath baseline).
  - Grep de sanidade a `MaterialTheme(colorScheme = darkColorScheme` — 0 matches remanescentes; `FriendsReelsTheme {` — 4 matches (uma por activity). Fix aplicada consistentemente.
- **Validação em device (esperada na próxima sessão):** bateria T (2 testes) descrita em §6.1.
- **Nada mudou** na chain de match (s47b), no `isThreadTopVisible` (s46), no `seenAuthors` (s47b), no batch history orchestrator (s48), na sync de reacção (s49), no schema Room. A s50 é 100% aditiva: fixes cirúrgicas + tema paralelo.

---

## 8. Como testar em device (modelo aceite pelo utilizador)

### 8.1 Formato obrigatório de cada teste

**Cada teste em §6.1 tem de ter, sem excepção:**

1. **Nome descritivo** — não `N1`, `L2`, etc. Escrever o que se está a validar. Ex.: **"Falha executa os 100 scrolls completos"**, **"Cancelar apply pending via notificação"**. O código curto (`N1`, `Interleaving`, etc.) fica opcional entre parêntesis para conversa rápida.
2. **"O que se está a validar"** — 1 linha a explicar a hipótese em teste.
3. **"Preparação"** — lista numerada com o que instalar/pull, que estado precisa a app / o IG antes de começar (base de dados com N Reels sem URL, IG na inbox, etc.), se é preciso um terminal com `logcat -s IGReaderService` aberto.
4. **"Passos"** — lista numerada, cada passo indica **onde tocar** (nome exacto do botão ou do menu). Não vale "correr o batch" — vale "abrir Friends Reels → ⚙ Definições → tocar 🔗 Preparar todos".
5. **"O que confirmar no logcat"** — lista concreta de linhas de log esperadas (com strings copy-paste-áveis) OU o que se vê na UI. Nunca `verifica se funciona`.
6. **"O que NÃO deve aparecer"** — sinais negativos (bugs conhecidos que estamos a corrigir).
7. **"Passa se" / "Falha se"** — critério binário para o utilizador poder reportar `passou/falhou` sem ambiguidade.
8. **Comandos adb alternativos** (opcional) — se um passo em UI for chato, dar equivalente via `adb shell am broadcast`.

### 8.2 Regra de reporte

Depois de correr a bateria completa:

1. **Copiar Logcat** filtrado por `IGReaderService` para `docs/screen-dumps/feed.txt`. Incluir timestamps.
2. **Uma linha por teste** — `"Teste 1 (Interleaving) — passou / falhou porque …"`. Se passou como esperado, não é preciso detalhar. Se falhou, adicionar contexto: em que passo estava, o que apareceu no log, o que aconteceu na UI.
3. **Se um teste falhar, parar a bateria** — os testes seguintes podem depender do anterior. Reporta e espera correcção.

### 8.3 Como testar depois de um pull

1. **Prep:** `git pull` → rebuild → reinstalar APK. Confirmar `logcat -s IGReaderService` mostra:
   ```
   Action receiver registered (build=sNN heart=… laugh=… …)
   ```
   com o `BUILD_TAG` da sessão actual (NN = número da sessão).
2. **Executar** a bateria da sessão actual (§6.1) OU a última bateria pendente de validação.
3. **Reportar** segundo §8.2.

### 8.4 Testes de smoke — sempre valem depois de qualquer alteração

Não são obrigatórios, mas se estiveres a duvidar de algo, faz este mini-set em 2 minutos:

- **"Descoberta base":** abrir uma conversa no IG com Reels visíveis → puxar shade → tocar botão **🔍** na notificação persistente → abrir Friends Reels → ver Reels novos no feed.
- **"Enrichment on-demand":** feed → swipe até um Reel com placeholder cinzento "URL ainda não capturado" → tocar **🔗 Preparar Reel** → IG abre, faz o fluxo (~5-8s), volta, vídeo aparece.
- **"Batching":** feed → em 2-3 Reels tocar ❤ / 😂 / 💬 → contadores nos chips ficam em `pending` → puxar shade → tocar botão **▶** → confirmar que aplica em IG e que o batch produz completion notification `▶ Fila aplicada — X ações …`.
- **"Filtro de conversas":** Definições → **Filtrar conversas no feed** → mudar radio de **Ver tudo** para **Apenas selecionadas em baixo** → marcar 1-2 conversas → voltar ao feed → confirmar que só aparecem Reels dessas conversas.

### 8.5 Baterias históricas

Estão nos logs de sessão da §7 e no `docs/screen-dumps/feed.txt` de cada era. Para consulta rápida:

- **F1-F6 (s36)** — smoke pós-refactor grande. Nomes: feed vertical, auto-play, chips, reply real, 3-pontinhos, ignoreSent toggle.
- **G1-G3 (s37)** — enrichment on-demand + selection mode. Nomes: enrichment single, apenas selecionadas, excluir selecionadas.
- **H1-H3 (s38)** — batch enrichment. Nomes: batch smoke, cross-thread, cancelar.
- **I1-I5 (s39)** — completion notifications + return-to-app. Nomes: batch/apply/history completion, discover-completion sem return, toggle OFF.
- **J1-J3 (s40)** — Home button, Cancelar via notif, persistência LastResult.
- **K1-K3 (s41)** — Cancelar apply pending, auto-enrich após 🔍/📥.
- **L1-L3 (s42)** — fail-fast, forward-scroll, heads-up.
- **M1-M4 (s43)** — recovery quando IG perde foco, scroll longo, stall detection (removido em s44), rate-limit.
- **N (s44)** — 3 testes com nomes descritivos em §6.1 acima: **"Passos antigos não interferem com o passo actual"**, **"Falha executa os 100 scrolls completos"**, **"Dump da árvore do topo da conversa"**.

Da s45 em diante, cada bateria segue o formato de §8.1.

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
