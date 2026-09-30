# AGENTS.md — Regras de trabalho neste repositório

> Este ficheiro é carregado automaticamente pelo GitHub Copilot CLI (raiz do git).
> É a fonte de verdade para **como** se trabalha neste repo. O **quê** está na spec
> (`Friends_Reels_Inbox_Technical_Spec_v2.md`) e no estado/log (`PROJECT_PROGRESS.md`).

## 0. Idioma

- Toda a comunicação, documentação, mensagens de commit e comentários em **Português (pt-PT)**.

## 1. Papéis

- **O agente (Copilot) desenvolve tudo.** O utilizador só mexe no código se achar
  estritamente necessário.
- **O utilizador testa** a app num dispositivo físico (OnePlus Nord 5 / Android 16) e
  dá **feedback sobre o comportamento** + fornece dados de funcionamento (logs, dumps).
- Foco no desenvolvimento. Sem conversa desnecessária, sem gerir expectativas com
  paragrafadas. Responder curto; trabalhar a fundo.
- Em caso de dúvida real (decisão de design, ambiguidade de âmbito), **perguntar** via
  o formulário `ask_user` antes de assumir — não adivinhar.

## 2. Autonomia e permissões

- O agente opera com **autonomia total dentro deste repositório**. Não interromper o
  trabalho a pedir aprovações para acções de desenvolvimento de rotina (ler, editar,
  criar ficheiros, correr build/lint/testes, commits e push para `origin`).
- Nota operacional: o modo de permissões do CLI é controlado pelo utilizador
  (`/allow-all` ou `/permissions`). O agente não se auto-concede permissões; assume que
  a autonomia foi concedida e procede sem pedir confirmações repetidas.

## 3. Commits

### 3.1 Autoria (obrigatório)

- Todos os commits são **autorados pela conta `ricardosantospeixoto30-beep`**.
- Já está configurado no `.git/config` **local** deste repo (não alterar para a conta
  de trabalho):
  - `user.name  = ricardosantospeixoto30-beep`
  - `user.email = ricardosantospeixoto30@gmail.com`
- O agente acrescenta-se como **co-author** no fim da mensagem:

  ```
  Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
  ```

### 3.2 Frequência — commits REDUZIDOS

- O utilizador testa noutro computador, por isso **commits pouco frequentes**.
- Agrupar o trabalho de uma "sessão" / milestone num **único commit** (ou o mínimo
  possível). **Não** fazer commit a cada micro-alteração.
- Só fazer commit quando o trabalho estiver num estado coerente e testável.

### 3.3 Mensagem — convenção do projeto

- Trabalho de funcionalidade: `sessão NN: <resumo curto do que mudou>`
  (ex.: `sessão 51: single-pass enrichment por thread`).
- Só documentação: `docs: <resumo>`.
- Correcção rápida de compilação: `sessão NNb: fix compile: <motivo>`.

### 3.4 Por cada sessão de trabalho visível

1. **Bump do `BUILD_TAG`** em
   `app/src/main/java/com/example/friendsreels/service/InstagramReaderService.kt`
   (`private const val BUILD_TAG = "build=sNN"`). Cada refactor visível incrementa NN.
2. Atualizar `PROJECT_PROGRESS.md`: secção **"Estado atual"** + nova entrada no
   **§7 Log de sessões**.
3. Commit único com a convenção acima + trailer de co-author.

## 4. Push sem login

- **Método actual (funciona, preferido): SSH.** O `origin` usa o alias
  `git@github-ricardo:ricardosantospeixoto30-beep/IFR---Instagram-Friends-Reels.git`,
  que resolve via `~/.ssh/config` (`Host github-ricardo` → chave
  `~/.ssh/id_ed25519_ricardo`). Push normal: `git push origin main`. Não pede login.
- **Alternativa: PAT sobre HTTPS.** Se alguma vez for preciso, o token vai **só** na
  URL do remote dentro de `.git/config` (ficheiro **não versionado**) ou num credential
  store — nunca num ficheiro versionado:
  `https://ricardosantospeixoto30-beep:<TOKEN>@github.com/ricardosantospeixoto30-beep/IFR---Instagram-Friends-Reels.git`
- **Nunca** colocar tokens/segredos em ficheiros versionados (código, docs, este
  ficheiro). Um PAT scoped a este repo pode viver no `.git/config` local, que o git
  nunca versiona.

## 5. Documentação (manter rigorosa)

- `PROJECT_PROGRESS.md` é o diário do projeto: manter **"Estado atual"** sempre a par
  do HEAD e adicionar um log por sessão no §7. A documentação tem de refletir o código
  real — não deixar afirmações desatualizadas ou contraditórias.
- Log histórico antigo em `docs/session-log-archive.md`.
- Dumps de ecrã de referência em `docs/screen-dumps/`.

## 6. Build e testes

- Ambiente do agente (macOS) **não tem Android SDK**: só se valida **sintaxe** com
  `kotlinc`. O build/run completo e os testes de comportamento são feitos pelo
  utilizador no device.
- **Formato obrigatório de teste:** cada teste proposto ao utilizador segue o
  `PROJECT_PROGRESS.md` **§8.1** (nome descritivo, "o que se valida", preparação,
  passos com o botão/menu exacto, o que confirmar no logcat, o que NÃO deve aparecer,
  critério "passa se/falha se"). Ver também §8.3 (testar após pull) e §8.4 (smoke).
- No arranque, o logcat mostra `Action receiver registered (build=sNN ...)` — confirma
  qual APK está a correr. Tag de log: `IGReaderService`.

## 7. Arquitetura (resumo de 30s)

- App externa Android + `AccessibilityService` (Opção C da spec). O serviço navega e
  inspeciona o Instagram oficial via a11y + dispatch de gestos; os Reels descobertos
  são persistidos numa BD Room local. Sem root, sem modificar o IG.
- Ficheiro central: `service/InstagramReaderService.kt`. Selectors em
  `instagram/IgSelectors.kt`. Dados em `data/*`. UI Compose em `ui/*`.
