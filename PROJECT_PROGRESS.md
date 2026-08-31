# PROJECT_PROGRESS — Friends Reels Inbox

> Ficheiro cumulativo de acompanhamento do projeto, conforme exigido pela spec §19.
> Atualizar sempre que houver decisões, investigação, testes ou mudanças relevantes.

---

## Estado atual

**Fase atual:** Fase 1 (PoC → MVP). Sessões 37-42 validadas em device. s43 introduziu 5x mais scrolls + IG-focus recovery entre steps mas o log revelou 2 bugs (stall detection falsa positiva, callbacks stale interleaving). s44 removeu a stall detection, adicionou epoch guard, e trouxe um botão de Dump em Diagnóstico — que capturava a UI errada porque tocar rouba foco ao IG. s45 corrigiu com dump adiado + Toast countdown; validado em device (`docs/screen-dumps/dump.txt` mostra o topo da conversa de `astrid_gutierrez` com `view_profile_button`, `user_avatar`, `network_attribution`, `other_user_full_name_or_username`). **s46 usa esses selectors para implementar `isThreadTopVisible` e integrar em `locateReelWithScroll` (aborta backward budget cedo) e `doHistoryScroll` (para de scrollar quando genuinamente chega ao topo).**
**Última atualização:** 2026-08-31 (sessão 46 — detecção real de topo de conversa).
**Arquitetura escolhida:** Opção C — app externa Android + `AccessibilityService`.
**HEAD actual:** `build=s46`.

### Como continuar na próxima sessão (quick start)

1. **Pull** do repo. Confirmar `Action receiver registered (build=s46 ...)`.
2. **Ler primeiro:** esta secção "Estado atual", §6 "Próximos passos", §7 log, **§8 "Como testar" (regras obrigatórias de formato de teste — cada bateria em §6.1 deve seguir §8.1)**.
3. **Ficheiros-chave:**
    - `instagram/IgSelectors.kt` — objecto `Thread` ganhou 4 novas constantes: `HEADER_VIEW_PROFILE_BUTTON`, `HEADER_USER_AVATAR`, `HEADER_OTHER_USER_FULLNAME`, `HEADER_NETWORK_ATTRIBUTION` (identificam o header "start-of-conversation" que aparece quando a `message_list` chega ao topo absoluto).
    - `service/InstagramReaderService.kt` — nova helper `isThreadTopVisible(root)` verifica presença dos 4 selectors acima (view_profile_button primeiro, os outros como fallback). Integrada em `locateReelWithScroll` (aborta backward budget cedo, log `LOCATE: thread top reached at scroll N/100 — skipping remaining backward budget.`) e em `doHistoryScroll` (log `HISTORY: thread top reached ... after N scrolls — stopping.`). Dump com delay (s45) mantém-se. Epoch guard (s44) mantém-se.
    - `ui/settings/SettingsActivity.kt` — botão de Dump com delay (s45), sem alterações na s46.
    - Dumps: `docs/screen-dumps/dump.txt` (s45 — mostra o topo da conversa, base para os selectors da s46).
4. **Constraints:**
    - Testes só no OnePlus Nord 5 / Android 16.
    - macOS deste ambiente não tem Android SDK, só validar sintaxe com kotlinc.
    - Cada refactor visível deve bumpar `BUILD_TAG`.
5. **UX actual em device:**
    - **Home:** protagonismo ao `▶ Abrir o meu feed`. Descoberta (🔍, 📥, 🔗 Preparar URLs (N) condicional). Configuração.
    - **Feed:** full-screen VerticalPager. Auto-play inline. Chip único de reacção. Menu ⋮.
    - **Definições:** 3 toggles + Filtrar conversas + Preparar URLs em lote + Diagnóstico com botão "🌳 Dump" que conta 5s antes de dumpar.
    - **Notificação persistente:** 3 botões (🔍 🔗 ▶) + progresso + Cancelar durante lotes.
    - **Notificação de conclusão:** heads-up.
6. **Limitações conhecidas:**
    - Matching por `reelAuthor` — 2 Reels do mesmo criador na mesma conversa colidem.
    - Locate com 100 backward × 300ms + 5 forward × 300ms = ~35s worst case por Reel. **s46:** worst-case só se aplica em conversas longas onde o Reel não está no histórico visível; quando a conversa é curta, `isThreadTopVisible` corta imediatamente.
    - Enrichment success ~5-8s; fail depende do tamanho da conversa (agora rápido em conversas curtas graças à s46, lento em conversas gigantes com Reels em falta).
    - Batch enrichment partilha `pendingCopy` com o fluxo on-demand.
    - Consulta de respostas anteriores dentro da app (spec §5) — não temos sync.
    - Reacção "actual" só reflecte as que corremos via app.
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
- ✅ **s45 — dump com atraso de 5s + Toast countdown.** Validada em device (`docs/screen-dumps/dump.txt` capturou topo da conversa `astrid_gutierrez` com `view_profile_button`, `user_avatar`, `network_attribution`, `other_user_full_name_or_username`).
- 🟡 **s46 — detecção real de topo de conversa (`isThreadTopVisible`) integrada em `locateReelWithScroll` e `doHistoryScroll`.** Pronta em código; aguarda validação em device (P1/P2).

### 6.1 Próxima sessão — arranque

**Estado no fim da s46:** substituição da stall detection (removida na s44) pela detecção real via selectors do dump da s45.
1. **`Thread.HEADER_VIEW_PROFILE_BUTTON` (+3 fallback constantes)** em `IgSelectors.kt` — capturados do dump da s45 no topo da conversa `astrid_gutierrez`.
2. **`isThreadTopVisible(root)`** no serviço — probe `view_profile_button` primeiro (o mais exclusivo), depois `user_avatar` / `other_user_full_name_or_username` / `network_attribution` como fallback caso IG renomeie um deles.
3. **`locateReelWithScroll`** — depois de enumerar candidatos e não achar match, se `isThreadTopVisible == true` pula o resto do budget backward, salta directamente para `locateReelWithForwardScroll` (se houver budget), ou termina com `onDone(null)`. Log: `LOCATE: thread top reached at scroll K/100 — skipping remaining backward budget.`
4. **`doHistoryScroll`** — check antes de cada scroll. Se topo visível, chama `finishHistory(state)`. Log: `HISTORY: thread top reached (view_profile_button visible) after N scrolls — stopping.`
5. **Nada mais mudou** — dump com delay (s45), epoch guard (s44), stall detection removida (s44), 100 backward + 5 forward + 300ms settle (s43), `runInInstagram` entre steps (s43), tudo intacto.

**Bateria proposta — Sessão 46 (nomes descritivos + passos concretos, formato §8.1):**

Se um teste falhar, para e reporta — não passes ao seguinte. Ambos são não-destrutivos.

#### Teste 1 — "Batch enrichment aborta cedo quando chega ao topo da conversa" (`TopStop-Batch`)

**O que se está a validar:** um Reel na DB com URL null cujo autor não existe na conversa activa faz o `locateReelWithScroll` scrollar até ao topo e depois parar imediatamente em vez de esgotar os 100 scrolls.

**Preparação:**
1. `git pull` no telemóvel-de-testes; recompilar e reinstalar o APK em `build=s46`.
2. Confirmar no logcat inicial: `Action receiver registered (build=s46 ...)`.
3. Escolher **uma conversa 1-a-1 CURTA** (~20-40 mensagens no total; mesma da s45 se possível, `astrid_gutierrez` ideal) onde tens **1 Reel na DB com autor inexistente** (podes inventar via `INSERT` manual em `adb shell` na Room DB, ou seleccionar um Reel já apagado no IG). Alternativa mais simples: criar um Reel fake com `reelAuthor="foobar_notexists_9999"` associado à threadTitle desta conversa.
4. Abrir `logcat -s IGReaderService` num terminal.

**Passos:**
1. Abrir Friends Reels → **⚙ Definições** → **"🔗 Preparar todos"**.
2. Deixar o batch correr sobre o Reel fake.
3. Ficar quieto (não abrir shade, não mudar de app).

**O que confirmar no logcat:**
- `ENRICH_ALL: step K/N reelId=X author=foobar_notexists_9999 thread='<titulo>'`.
- Sequência de `LOCATE: scroll M/100 author=foobar_notexists_9999` (com rate-limit — só 1/10/20/…).
- Em algum ponto **antes de atingir 100 scrolls**: `LOCATE: thread top reached at scroll K/100 (view_profile_button visible) — skipping remaining backward budget.`
- Imediatamente a seguir: `LOCATE_FWD: scroll 1/5 ...` (o forward fallback continua a ser tentado).
- Depois: `LOCATE_FWD: could not find reelId=X author=foobar_notexists_9999 after 5 forward scrolls.`
- Batch conclui com `ENRICH_ALL: batch complete (succeeded=0 failed=1)`.

**NÃO** deve aparecer:
- `LOCATE: scroll 100/100 ...` (indicaria que o budget esgotou sem detectar o topo).
- `LOCATE_FWD: scroll 5/5` **antes** do `thread top reached`.

**Passa se:** o log mostra `thread top reached at scroll K/100` para algum K < 100, com K correspondendo ao número de scrolls necessário para chegar ao topo da conversa. Duração total do step ≪ 30s.
**Falha se:**
- **F1:** `thread top reached` nunca aparece → `isThreadTopVisible` não encontrou nenhum dos 4 selectors. Verificar dump manual desta conversa (via botão + delay) — se `view_profile_button` estiver com outro `resource-id`, adicionar ao array em `isThreadTopVisible`.
- **F2:** batch faz os 100 scrolls completos → mesma causa que F1. Ver F1.
- **F3:** `thread top reached` aparece imediatamente à primeira iteração → o dump foi obtido numa conversa onde o topo já estava visível quando o batch arrancou. Testar com conversa maior onde é necessário scrollar.

---

#### Teste 2 — "History-scroll para de scrollar quando genuinamente chega ao topo" (`TopStop-History`)

**O que se está a validar:** o botão `📥 Descobrir histórico (scroll auto)` para no topo real da conversa em vez de esperar pelos 5 scrolls vazios consecutivos.

**Preparação:**
1. Mesmo build `s46`.
2. Escolher **uma conversa 1-a-1 curta com pelo menos 1 Reel recebido** (para gerar inserts). O `astrid_gutierrez` da s45 serve — tem mensagens até `6/06, 11:06 DA TARDE` e o header logo por cima.
3. Limpar Room DB para esta thread (via `adb shell pm clear com.example.friendsreels` — cuidado, apaga tudo) ou aceitar duplicados detectados como skips.
4. Abrir `logcat -s IGReaderService`.

**Passos:**
1. Abrir o Instagram.
2. Ir à conversa.
3. Puxar o shade e tocar 📥 na notificação persistente (OU voltar ao Friends Reels → Home → 📥).
4. Deixar o history-scroll correr — vai fazer múltiplas iterações de `ACTION_SCROLL_BACKWARD`.

**O que confirmar no logcat:**
- `HISTORY: doHistoryEnumerate ...` a cada scroll (inserts / skips como habitual).
- Múltiplos `HISTORY: scroll K/100 via ACTION_SCROLL_BACKWARD accepted` até chegar ao topo.
- Em algum ponto: `HISTORY: thread top reached (view_profile_button visible) after N scrolls — stopping.`
- Segue com `HISTORY: finished — thread='<titulo>' scrolls=N ...`

**NÃO** deve aparecer:
- `HISTORY: stopping — 5 consecutive empty scrolls.` (esse era o fallback pré-s46; agora só deve activar em conversas gigantes onde o topo não está próximo).
- `HISTORY: stopping — safety cap 100 scrolls hit.` (só se a conversa for enorme e a s46 não detectar topo).

**Passa se:** aparece a linha `HISTORY: thread top reached ... after N scrolls`, com N igual ao número real de scrolls necessário para chegar ao topo (≪ 100 nesta conversa curta).
**Falha se:**
- **F1:** `thread top reached` nunca aparece; para por `5 consecutive empty scrolls` → o topo passou-lhe ao lado. Ver F1 do Teste 1.
- **F2:** para com `safety cap 100 scrolls hit` → topo não detectado. Reforçar a bateria — pode ser que os selectors variem por versão do IG.

---

**Priorização depois de P validado:**

1. **Sync de reacção actual (spec §7)** — ler `message_reactions_pill_container` de cada Reel na DM e persistir a reacção actual, para reflectir no chip do feed. Adiciona coluna `currentReaction` a `ReelEntity` (migração Room).
2. **Match estrito por Reel URL no `locateReelWithScroll`** — para casos de 2 Reels do mesmo criador na mesma conversa. Precisa de expandir o viewer para cada match candidato — não trivial. Investigar alternativas (posição relativa no thread, timestamp).
3. **Cosmético:** thumbnails / preview no feed; ordem dos chips por `createdAt`; indicador visual de direcção de swipe.
4. **Deep-link `instagram://direct/t/<thread_id>`** — investigar onde IG expõe o thread_id (dump da inbox).
5. **Tuning de latência de scroll** — perfilar `LOCATE_SCROLL_SETTLE_MS` em conversas reais.

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

> **Sessões 1-32 arquivadas** em [`docs/session-log-archive.md`](docs/session-log-archive.md). Cobrem o skeleton, PoCs 1-7 (long-press, direcção, reagir, responder, copiar URL) e PoC-8 iterações 1-3 (Room + feed + batching + history-scroll + player embed).
>
> Abaixo ficam as sessões desde a s33 (PoC-9 em diante), que definem o estado corrente da arquitectura.

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
- **Validação em device (fim da sessão):** utilizador confirmou G1 (enrichment on-demand), G2 ("Apenas selecionadas"), G3 ("Excluir selecionadas") todos ok. Log em `docs/screen-dumps/feed.txt` mostra ENRICH_URL a completar o fluxo end-to-end: locate → viewer tap → share → copy → `promoted 1 discovery-only row`. **s37 fechada. Sessão fecha aqui.**
- **Limpeza de fecho:**
    - Log de sessões 1-32 arquivado em `docs/session-log-archive.md` para manter o `PROJECT_PROGRESS.md` focado.
    - Removidos strings.xml de UI antiga (poc_tools_*, feed_queue_*, feed_pending_badge_*, settings_placeholder_*, settings_invert_swipe_*, feed_menu_open_thread_in_ig, etc.).
    - Removidos dumps não referenciados (`docs/screen-dumps/2025-08-28-poc5-reactions.txt`, `dump-menu.txt`).
    - §8 substituída pelo modelo de teste consolidado (baterias G/F ficaram nos logs de §7).
    - `README.md` actualizado com estado de MVP fechado.
- **Próximo arranque:** ver §6.1 acima.

---

### 2026-08-31 — Sessão 38 (Ricardo + Copilot CLI) — enrichment em batch

- **Contexto:** utilizador pediu para continuar do ponto onde parámos na s37. Segundo §6.1 pós-s37 o próximo passo priorizado era o **enrichment em batch** (o #2, autocontido) — remove a fricção do fluxo actual (tocar Reel a Reel no botão "🔗 Preparar Reel" do feed). Feature única desta sessão.
- **Design escolhido:**
  - Reutilizar toda a chain PoC-7 já validada. Não escrever nada novo do lado do IG.
  - Cada Reel usa o mesmo `startEnrichmentForReel(reel)` que a s37 usa para o on-demand.
  - O executor corre no main handler, mas o loop de espera "URL apareceu na DB?" corre no `serviceScope` (IO) com polling a cada 500ms até um deadline por passo (`BATCH_ENRICH_STEP_TIMEOUT_MS = 45s`).
  - Ordem dos Reels: agrupar por `threadTitle`, ordenar grupos pelo `discoveredAt` mais antigo (mesma heurística do `applyPendingActions`), intra-grupo `ASC` — minimiza `navigateToThreadAsync`.
  - Progresso: singleton `BatchEnrichmentBus` com `MutableStateFlow<State>` — sem broadcast/IPC porque a11y service e Activity estão no mesmo processo.
  - Cancelamento: flag boolean `batchEnrichmentCancelled` verificada entre passos. O Reel actual termina para não deixar IG na share sheet com clipboard pendente.
- **Motor no `InstagramReaderService.kt`:**
  - Novas actions `ACTION_ENRICH_ALL_MISSING_URLS` e `ACTION_ENRICH_ALL_CANCEL` (sem extras — a lista vem toda do DAO).
  - `enrichAllMissingUrls()` carrega `reelDao.allMissingUrls()`, publica no bus, `mainHandler.post { runInInstagram { processBatchEnrichmentStep(0, 0, 0) } }`.
  - `processBatchEnrichmentStep(reels, index, succeeded, failed)`:
    - Guard: se cancelado ou index==size → publica `LastResult(succeeded, failed, cancelled)` no bus, limpa flag, sai.
    - Publica progresso 1-based no bus.
    - IO scope: re-lê a row (caso outro caminho a tenha enriquecido entretanto — evita repetir trabalho), `startEnrichmentForReel(fresh)`, poll de 500ms com deadline 45s. Se URL aparecer → succeeded++; senão failed++, limpa `pendingCopy` residual.
    - Delay `BATCH_ENRICH_SPACING_MS = 1500ms` antes de agendar próximo passo — dá margem à BACK×2 do PoC-7 assentar.
  - `cancelBatchEnrichment()` só liga a flag (no-op se não estiver a correr).
  - Novas constantes: `BATCH_ENRICH_STEP_TIMEOUT_MS`, `BATCH_ENRICH_POLL_INTERVAL_MS`, `BATCH_ENRICH_SPACING_MS`.
  - Estado: `batchEnrichmentInProgress`, `batchEnrichmentCancelled`.
- **Bus (`service/BatchEnrichmentBus.kt`, novo):**
  - `data class State(running, currentIndex, total, lastResult)`.
  - `data class LastResult(succeeded, failed, cancelled)`.
  - Singleton object com `MutableStateFlow` (private) exposto via `StateFlow`.
- **DAO (`data/ReelDao.kt`):**
  - `suspend fun allMissingUrls(): List<ReelEntity>` — ordenado por (threadTitle, discoveredAt).
  - `fun observeMissingUrlCount(): Flow<Int>` — contador live consumido pela UI para desactivar o botão quando N=0.
- **`SettingsViewModel.kt`:**
  - Expõe `missingUrlCount: StateFlow<Int>` (do DAO) e `batchEnrichmentState: StateFlow<BatchEnrichmentBus.State>`.
  - Métodos `startBatchEnrichment()` e `cancelBatchEnrichment()` fazem broadcast das actions.
- **`SettingsActivity.kt`:**
  - Nova secção `BatchEnrichmentSection(vm)` entre "Filtrar conversas no feed" e "Ferramentas de diagnóstico".
  - Idle + N=0: mensagem "Todos os Reels descobertos já têm URL".
  - Idle + N>0: `N Reel(s) sem URL` + botão `🔗 Preparar todos` (com Toast informativo).
  - Running: `A preparar K de N…` + `LinearProgressIndicator(K/N)` + botão `Cancelar` (com Toast).
  - Após execução (idle + `lastResult != null`): "Última execução: X preparado(s), Y falharam" (ou variante `(cancelada)`).
- **Strings novas** (`res/values/strings.xml`): `settings_batch_enrich_title/subtitle/pending/none/start/running/cancel/last_result/last_result_cancelled/start_toast/cancel_toast` (11 strings).
- **`BUILD_TAG` bumped para `build=s38`.** Sem alteração de schema Room.
- **Ficheiros novos:**
  - `service/BatchEnrichmentBus.kt`
- **Ficheiros alterados:**
  - `service/InstagramReaderService.kt` — 2 novas actions, `enrichAllMissingUrls`, `processBatchEnrichmentStep`, `cancelBatchEnrichment`, 2 novos campos de estado, 3 constantes, `BUILD_TAG=s38`, log do receiver.
  - `data/ReelDao.kt` — `allMissingUrls`, `observeMissingUrlCount`.
  - `ui/settings/SettingsViewModel.kt` — `missingUrlCount`, `batchEnrichmentState`, `startBatchEnrichment`, `cancelBatchEnrichment`.
  - `ui/settings/SettingsActivity.kt` — novo composable `BatchEnrichmentSection` + imports (LinearProgressIndicator, Toast).
  - `res/values/strings.xml` — 11 strings novas.
  - `PROJECT_PROGRESS.md` — Estado atual, quick start, §6/6.1 actualizadas, este log.
- **Validação em ambiente do agente:** kotlinc compile-check com JDK 21 sobre os 5 ficheiros alterados/novos — nenhum erro real (só erros semânticos esperados: `overrides nothing`, `unresolved reference`, `File.root: internal` por ausência de classpath Android). Sintaxe OK.
- **Validação em device (esperada na próxima sessão):** bateria H1/H2/H3 descrita na §6.1. Se falhar num Reel específico, o log mostra `ENRICH_ALL: step K result stepOk=false` — o loop continua para os restantes, e o resultado final agrega os falhados.
- **Nada mudou** nas primitivas isoladas PoC-3/5/6/7, no discover, no player, na notificação, na navegação PoC-9, no batching de reactions/replies, ou no filtro por selection mode. O batch enrichment é uma feature aditiva no cimo da chain do PoC-7 + PoC-9.

---

### 2026-08-31 — Sessão 39 (Ricardo + Copilot CLI) — feedback de conclusão + return-to-app

- **Feedback do utilizador após validar s38 em device** (dump em `docs/screen-dumps/feed.txt`, timestamps 10:55→10:59):
  - **H1 ok:** lote de 1 Reel completou (log `ENRICH_ALL: batch complete (succeeded=1 failed=0)`) mas quando terminou o utilizador ficou dentro do IG sem sinal visível de que a operação tinha acabado. Difícil perceber quando é seguro voltar a mexer.
  - **H2 ok:** cross-thread confirmado (log `starting batch for 4 reel(s) across 2 thread(s)`, ordem Pedro Sardoeira → André Pinto respeitada).
  - **H3 ok:** cancelamento colaborativo funciona — Reel actual termina e a seguir batch pára.
  - **Observação:** ir do IG para a app para tocar em Cancelar "já dá uma mensagem qualquer nos logs" (i.e. o utilizador tem de mudar de app manualmente).
  - **Pedido explícito:** "a nível geral, se der para ter alguma mensagem visual ou notificação a dizer que acabou, ajuda" — aplicável a todas as ações longas (batching, enrichment, discovery).

- **Design escolhido nesta sessão:**
  1. **Canal separado para conclusão** (`friends_reels_status`) com `IMPORTANCE_DEFAULT` (o canal de controlo persistente continua em `LOW`). O default toca som e mostra badge — o utilizador nota mesmo estando em IG.
  2. **Notificação transiente ao terminar** (`NOTIF_ID_COMPLETION = 1003`), auto-cancel, `contentIntent → FeedActivity`, com `BigTextStyle` para caber o corpo + o hint "Toca para abrir o feed".
  3. **Return-to-app** opcional, controlado por nova pref `PREF_RETURN_TO_APP_ON_FINISH` (default `true`). Usa `FLAG_ACTIVITY_NEW_TASK | REORDER_TO_FRONT | SINGLE_TOP` — traz FeedActivity para a frente sem resetar o task.
  4. **Progresso na notificação persistente** também para o batch enrichment (já existia para `applyPendingActions` e `discoverReelsHistory`) — nova função `updateBatchEnrichProgressNotification`.

- **Motor no `InstagramReaderService.kt`:**
  - Novos helpers `postCompletionNotification(title, body)` (canal status, `BigTextStyle`, tap → FeedActivity), `returnToAppIfEnabled()` (lê a pref, lança FeedActivity), `updateBatchEnrichProgressNotification(current, total)` (overwrite do control notif com `setProgress`), `createNotificationChannel()` agora regista os DOIS canais.
  - Wire nas ações longas (todas chamam completion + optional return-to-app no fim):
    - `enrichAllMissingUrls`: `updateBatchEnrichProgressNotification` no arranque + a cada passo, `postCompletionNotification` no fim (títulos distintos para success/cancelled), `returnToAppIfEnabled` no fim. Empty batch (0 Reels sem URL) também posta completion `"0 preparado(s) · 0 falharam"`.
    - `applyPendingActions`: novos campos in-memory `applyPendingSucceeded`/`applyPendingFailed` incrementados em `finishStepAsync` (baseado no `status` que passa). `runBatchStep` no ramo terminal (`index >= steps.size`) posta completion `"▶ Fila aplicada — X · Y falharam"` + return-to-app. Empty queue também posta com 0/0.
    - `discoverReels`: no fim da coroutine, posta completion no main handler (`🔍 Descoberta concluída — X Reel(s) novo(s) em 'thread'` ou variante `Sem Reels novos em 'thread'`). Sem return-to-app (ação curta, o utilizador está a decidir em IG).
    - `discoverReelsHistory`: `finishHistory` agora chama completion `"📥 Histórico descoberto — X Reel(s) novo(s) em 'thread' (N scrolls)"` + return-to-app.
    - `handleClipboardCaptured` (single copy URL / single ACTION_ENRICH_REEL_URL): só posta completion se `success && !batchEnrichmentInProgress` — evita spam durante o lote. Body inclui `@autor em 'thread'`. Sem return-to-app (o utilizador estava em IG).
  - `BUILD_TAG` bumped para `build=s39`. Log de registo do receiver mantém-se estruturalmente, aparece `build=s39` no arranque.

- **UI (`SettingsActivity.kt`):**
  - Novo `SettingToggle` "Voltar à app quando terminar" (default ON) logo abaixo de "Ignorar Reels enviados". Persiste em `PREF_RETURN_TO_APP_ON_FINISH`.
  - Assinatura de `SettingsScreen` ganha `initialReturnToApp` + `onReturnToAppChange`.

- **Strings** (`res/values/strings.xml`, 17 novas):
  - `notif_channel_status_name/description` — nome/descrição do novo canal.
  - `notif_completion_tap_hint` — "Toca para abrir o feed."
  - `notif_completion_batch_enrich_title/body/body_cancelled` — 3 strings.
  - `notif_completion_apply_pending_title/body` — 2 strings.
  - `notif_completion_discover_title/body/body_empty` — 3 strings.
  - `notif_completion_history_title/body` — 2 strings.
  - `notif_completion_copy_url_title/body/body_no_author` — 3 strings.
  - `notif_enrich_all_progress` — "A preparar URLs %1$d/%2$d…".
  - `settings_return_to_app_title/subtitle` — 2 strings.

- **Ficheiros alterados:**
  - `service/InstagramReaderService.kt` — 2 novos helpers, wire em 5 code paths, novas prefs, `BUILD_TAG=s39`, 2 canais em `createNotificationChannel`.
  - `ui/settings/SettingsActivity.kt` — novo toggle + assinatura + wire com prefs.
  - `res/values/strings.xml` — 17 strings novas.
  - `PROJECT_PROGRESS.md` — Estado atual, quick start, §6/6.1 (bateria I), este log.
- **Validação em ambiente do agente:** kotlinc compile-check com JDK 21 — todos os erros são classpath-derived (`unresolved reference`, `overload resolution ambiguity` por Long/Int/Iterator sem stdlib inferível). Zero erros no código novo que não sejam idênticos ao baseline pré-s39. Sintaxe OK.
- **Validação em device (esperada na próxima sessão):** bateria I1-I5 descrita na §6.1.
- **Nada mudou** nos fluxos de dados, no schema Room, nos primitivos PoC-3/5/6/7, na navegação PoC-9, no filtro de selecção, na chain PoC-7, no bus `BatchEnrichmentBus` (a UI das Definições já mostrava o progresso via bus — isto adiciona-lhe uma **segunda** camada de feedback, via notificação, para quando o utilizador está fora das Definições).

---

### 2026-08-31 — Sessão 40 (Ricardo + Copilot CLI) — atalhos e persistência

- **Contexto:** o utilizador pediu para continuar autonomamente com features independentes. A s39 já tinha resolvido o feedback de conclusão; a s40 arruma três polish items que reduzem fricção sem depender do próximo teste em device.
- **Feature A — Home button "🔗 Preparar URLs em lote (N)":** `MainActivity` observa `AppDatabase.reelDao().observeMissingUrlCount()` via `collectAsState`. O botão aparece na secção "Descobrir Reels" só quando `N > 0`, com o contador live. Toca → mesmo broadcast que o botão nas Definições (`ACTION_ENRICH_ALL_MISSING_URLS`). Elimina a necessidade de entrar em Definições quando o objectivo é só "processar tudo o que está pendente".
- **Feature B — Cancel button na progress notification do batch:** `updateBatchEnrichProgressNotification` agora inclui uma action button `✕ Cancelar` (broadcast `ACTION_ENRICH_ALL_CANCEL`). Enquanto o batch corre, a notificação persistente mostra `A preparar URLs K/N…` + progresso + Cancelar — o utilizador não precisa de sair do IG para parar o lote (fecha o feedback deixado em aberto na s38 sobre o cancelamento exigir mudança de app).
- **Feature C — Persistir `LastResult`:** novas prefs privadas `PREF_LAST_ENRICH_HAS/SUCCEEDED/FAILED/CANCELLED`. `persistBatchEnrichResult` grava-as no terminal branch de `processBatchEnrichmentStep` (2 caminhos: empty e normal-terminal). Novo `restorePersistedBatchEnrichResult()` chamado em `onServiceConnected` seed-a o `BatchEnrichmentBus` com o resultado guardado — o Settings mostra correctamente "Última execução: X preparados, Y falharam" mesmo depois de o processo do serviço ser morto pelo Android (edge case: reboot, low memory, toggle a11y off/on).
- **`BUILD_TAG` bumped para `build=s40`.** Sem alteração de schema Room.
- **Ficheiros alterados:**
  - `MainActivity.kt` — observa `missingUrlCount` + novo callback + botão conditional.
  - `service/InstagramReaderService.kt` — 3 novas prefs, `persistBatchEnrichResult`/`restorePersistedBatchEnrichResult`, `onServiceConnected` chama restore, `updateBatchEnrichProgressNotification` ganha Cancel action, `processBatchEnrichmentStep` chama persist em 2 branches, `BUILD_TAG=s40`.
  - `res/values/strings.xml` — 2 strings novas (`home_prepare_urls_batch`, `notif_action_cancel`).
  - `PROJECT_PROGRESS.md` — Estado atual, quick start, §6/6.1 (bateria J), este log.
- **Validação em ambiente do agente:** kotlinc compile-check com JDK 21 — 31 "real errors" totais, todos em código pré-existente e todos derivados de classpath (K2 falha type inference sem stdlib inferível). Zero erros no código novo da s40. Sintaxe OK.
- **Validação em device (esperada na próxima sessão):** bateria I+J descrita na §6.1.
- **Nada mudou** nos primitivos PoC, na navegação PoC-9, no filtro de selecção, na chain PoC-7, no fluxo de completion notifs da s39. A s40 é aditiva.

---

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
