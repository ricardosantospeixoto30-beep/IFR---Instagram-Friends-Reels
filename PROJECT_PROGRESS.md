# PROJECT_PROGRESS — Friends Reels Inbox

> Ficheiro cumulativo de acompanhamento do projeto, conforme exigido pela spec §19.
> Atualizar sempre que houver decisões, investigação, testes ou mudanças relevantes.

---

## Estado atual

**Fase atual:** Fase 1 (PoC) — PoC-2 em curso (mapeamento de seletores do IG).
**Última atualização:** 2025-08-28 (sessão 2)
**Arquitetura escolhida:** Opção C — app externa Android + `AccessibilityService`.

---

## 1. Requisitos identificados (resumo da spec)

Ver `Friends_Reels_Inbox_Technical_Spec_v2.md` para o detalhe completo. Pontos-chave:

- Feed vertical/full-screen com um Reel por ecrã dos Reels **recebidos em DMs**.
- Descoberta de Reels em conversas existentes sem obrigar o utilizador a reencaminhar manualmente.
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

---

## 6. Próximos passos concretos

1. **[Utilizador]** Puxar o repo no PC do Android Studio, abrir o projeto, deixar sincronizar e correr no OnePlus Nord 5. Confirmar os pontos do §3 (skeleton a funcionar).
2. **[App]** Se o skeleton compilar e correr, avançar para PoC-2: mapear seletores (`view-id`, `content-description`, texto) das seguintes ecrãs no IG oficial:
   - Home (para chegar a Direct).
   - Direct/Inbox (lista de conversas).
   - Conversa individual (mensagens).
   - Mensagem com Reel (para long-press → "Copy link").
   - Mensagem com Reel + long-press → identificar item "Reply" para PoC-6.
   Fazer isto em **inglês e português** e guardar em `IgSelectors.kt`.
3. **[App]** Escrever o dump-tree utility na service (comando via `adb shell am broadcast`) que despeja a árvore de nodes no logcat quando estamos num ecrã de interesse. Isto acelera brutalmente o mapping.
4. **[App]** Implementar navegação Home → Direct → primeira conversa e reportar `content-description` do primeiro Reel encontrado. Corresponde a PoC-2 + parte de PoC-3.

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

### 2025-08-28 — Sessão 2 (Ricardo + Copilot CLI)

- **Skeleton validado no dispositivo real (OnePlus Nord 5, Android 16):** app abre, botão para "Ativar serviço de acessibilidade" funciona, e o `Logcat` mostra `InstagramReaderService connected` quando o serviço é ativado. ✅
- **Confirmado idioma do IG do utilizador:** Português (Portugal). Adicionar também suporte para Inglês porque é praticamente de graça.
- **PoC-2 iniciado:** foi adicionada ao `InstagramReaderService` a capacidade de despejar a árvore de acessibilidade do ecrã atual para o Logcat, mediante broadcast:
  - Ação: `com.example.friendsreels.ACTION_DUMP_TREE`
  - Comando adb: `adb shell am broadcast -a com.example.friendsreels.ACTION_DUMP_TREE`
  - Cada nó imprime `className`, flags de comportamento (`C`=clickable, `S`=scrollable, `L`=long-clickable, `E`=editable), `viewId`, `contentDescription`, `text`, e bounds.
- **Ajuste ao `accessibility_service_config.xml`:** removido `android:packageNames` para o dump funcionar em qualquer app (útil durante o mapping). Vai ser restringido de novo a `com.instagram.android` mais tarde, quando entrar em modo "produção".
- **Próximo passo do utilizador:** correr o dump nos ecrãs de interesse do IG (Home, Direct/Inbox, Conversa individual, mensagem com Reel, long-press num Reel) e enviar os outputs para eu construir o `IgSelectors.kt`.
