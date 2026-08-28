# Friends Reels Inbox — Technical Product Specification & Feasibility Plan

## 1. Objetivo

Criar uma funcionalidade chamada **Friends Reels** que permita reunir, numa experiência de feed vertical semelhante ao feed de Reels do Instagram, os Reels que foram enviados por amigos em conversas do Instagram.

O objetivo principal é eliminar a necessidade de abrir cada conversa, procurar manualmente no histórico e localizar cada Reel para o poder ver, reagir ou responder.

A experiência pretendida é:

- abrir o Friends Reels;
- ver um Reel por ecrã, em formato vertical/full-screen;
- fazer swipe para passar rapidamente entre os Reels recebidos;
- saber quem enviou cada Reel e em que conversa foi recebido;
- marcar Reels como vistos;
- reagir ao Reel;
- responder diretamente ao Reel, reproduzindo o comportamento de resposta a um Reel já existente numa DM do Instagram;
- continuar no feed depois de uma reação ou resposta;
- conseguir encontrar também Reels antigos já existentes nas conversas, sem ter de os reenviar manualmente para outra aplicação.

O projeto deve ser desenvolvido **do zero**.

---

## 2. Prioridade das soluções de integração

A prioridade do projeto deve seguir esta ordem estrita.

### Opção A — Integração diretamente na aplicação do Instagram

Esta é a solução preferida e deve ser investigada primeiro.

A experiência ideal é que o Friends Reels apareça como uma nova opção na barra de navegação inferior da aplicação móvel do Instagram, ao lado das áreas normais da aplicação.

Conceptualmente, pretende-se algo semelhante a:

`Home | Reels | DMs | Friends Reels | Pesquisa/FYP | Perfil`

A posição exata e o aspeto do ícone podem ser definidos posteriormente.

O importante é que, ao utilizar o Instagram normalmente, o utilizador tenha acesso ao Friends Reels **dentro da própria aplicação do Instagram**, sem precisar de mudar para uma aplicação externa para utilizar a funcionalidade principal.

Não assumir previamente que esta integração é possível. A investigação deve determinar qual é a forma concreta de a conseguir, caso exista.

### Opção A.2 / B — Integração dentro de outra área do Instagram

Caso não seja possível acrescentar uma nova opção na barra inferior, deve ser investigada uma solução alternativa que continue a colocar a experiência do Friends Reels **dentro da aplicação do Instagram**.

Por exemplo, pode ser através de uma opção/botão dentro da área de DMs ou de outro ponto da interface do Instagram.

Esta solução continua a ser preferida a uma aplicação externa, desde que a experiência principal do Friends Reels permaneça dentro da aplicação do Instagram.

### Opção C — Aplicação externa

Só quando as opções A e A.2/B forem demonstradas como inviáveis é que o projeto deve passar para uma aplicação externa.

Neste caso:

- a aplicação externa terá a experiência principal do Friends Reels;
- será usada para configurações e para tudo o que não possa ser integrado no Instagram;
- sempre que uma ação normal do Instagram for necessária e não puder ser reproduzida na aplicação externa, deve existir uma forma clara de abrir o contexto correspondente no Instagram.

**Regra de decisão:** não abandonar a Opção A apenas por ser mais difícil. A investigação deve tentar concretizá-la da melhor maneira possível. Só deve passar para A.2/B e posteriormente C quando os testes demonstrarem que a opção prioritária não consegue cumprir os requisitos essenciais.

---

## 3. Experiência principal do Friends Reels

### Feed

O Friends Reels deve apresentar um feed vertical/full-screen com **um Reel por ecrã**.

Comportamento padrão dos gestos:

- **Swipe para cima → próximo Reel**
- **Swipe para baixo → Reel anterior**

Deve existir uma definição opcional para inverter este comportamento:

- Swipe para cima → Reel anterior
- Swipe para baixo → próximo Reel

Esta definição é de **baixa prioridade**.

### Informação apresentada por Reel

Quando disponível, o feed deve mostrar:

- Reel original;
- nome/avatar da pessoa que enviou o Reel;
- conversa de origem;
- data aproximada de envio;
- texto/mensagem que acompanhava o Reel, caso exista;
- estado do Reel;
- indicação visual da reação atual, caso exista;
- indicação visual de que já houve resposta, caso exista.

A existência ou ausência de uma mensagem que acompanha o Reel não altera o requisito: **a reação e a resposta estão associadas ao próprio Reel recebido**.

---

## 4. Reações

O Friends Reels deve permitir reagir ao Reel, mas não deve apresentar as ações normais de **Like** ou **comentário público** do Reel dentro da experiência do Friends Reels.

Na primeira versão, podem ser usadas apenas as duas reações equivalentes às opções mais simples pretendidas:

- ❤️
- 😂

Mais reações podem ser adicionadas posteriormente.

### Comportamento da reação

A reação deve estar associada ao **Reel/mensagem do Reel na conversa original**, e não simplesmente a um estado interno criado pela aplicação.

O objetivo é que:

1. o utilizador veja um Reel no Friends Reels;
2. escolha uma reação;
3. essa reação seja enviada para a mensagem correspondente na conversa original do Instagram, quando a integração permitir;
4. a reação que aparece no Friends Reels represente a reação atualmente existente nessa mensagem.

Se o utilizador alterar a reação posteriormente diretamente no Instagram, o Friends Reels deve refletir a nova reação na próxima sincronização/atualização.

Se a reação for removida, o Reel deve deixar de estar marcado como **Reagido**.

---

## 5. Responder a um Reel

O Friends Reels deve ter uma ação de **Responder**.

Esta ação não corresponde a comentar publicamente o Reel.

O objetivo é reproduzir o comportamento nativo do Instagram para **responder a uma mensagem que contém um Reel numa DM**.

Conceptualmente, no Instagram, quando um utilizador responde a um Reel recebido, a resposta fica associada à mensagem original do Reel, aparecendo no contexto da conversa como uma resposta àquele Reel.

O Friends Reels deve procurar reproduzir esse mesmo comportamento final:

1. o utilizador vê o Reel no Friends Reels;
2. toca em **Responder**;
3. escreve a mensagem;
4. a mensagem deve ser enviada como resposta à mensagem original que contém aquele Reel;
5. no Instagram, o resultado deve aparecer como uma resposta normal àquele Reel, tal como aconteceria se o utilizador respondesse manualmente dentro da conversa.

A ausência de texto originalmente associado ao Reel **não impede** que o utilizador lhe responda.

### Respostas anteriores

O Friends Reels deve permitir perceber visualmente que o utilizador já respondeu àquele Reel.

Quando o utilizador abrir a ação **Responder** para um Reel que já tenha respostas suas, deve ser possível consultar as respostas anteriores antes de escrever uma nova resposta.

Deve também ser possível enviar uma nova resposta ao mesmo Reel.

A indicação de que já houve resposta pode ser apresentada através de qualquer solução visual clara. Não é obrigatório copiar exatamente os elementos gráficos do Instagram.

---

## 6. Regra crítica para reação e resposta

A aplicação não deve criar uma falsa sensação de que uma ação foi enviada para o Instagram quando isso não aconteceu.

### Se for possível executar a ação dentro do Instagram

A reação ou resposta deve ser executada no contexto original da DM do Instagram.

### Se não for possível executar a ação dentro do Instagram

O Friends Reels **não deve apresentar um campo de escrita ou um mecanismo interno que pareça enviar a resposta**, se esse envio real não puder ser efetuado.

Nesse caso, deve existir uma ação clara para continuar no Instagram.

Para responder, essa ação deve abrir o Instagram **diretamente na conversa original e no contexto do Reel correspondente**, para que o utilizador possa responder manualmente.

Este comportamento é obrigatório no cenário em que a resposta não possa ser enviada diretamente pelo Friends Reels.

---

## 7. Estados dos Reels

Os estados não devem ser tratados como mutuamente exclusivos.

Um Reel pode, por exemplo, estar simultaneamente:

- **Não visto**;
- **Visto**;
- **Reagido**;
- **Respondido**.

Também devem existir estados técnicos para situações como falha ou indisponibilidade quando necessário.

A lista conceptual inclui:

- `UNSEEN`
- `SEEN`
- `REACTION_SENT`
- `REPLIED`
- `FAILED`
- `UNAVAILABLE`

### Estado Reagido

**Reagido** significa que existe atualmente uma reação enviada pelo utilizador para aquele Reel/mensagem na conversa.

Não deve significar apenas que o utilizador reagiu alguma vez no passado.

Se a reação for retirada, o estado deixa de ser Reagido.

### Estado Respondido

**Respondido** significa que existe pelo menos uma resposta do utilizador associada àquele Reel.

---

## 8. Seleção de conversas, pessoas e grupos

O utilizador deve conseguir controlar exatamente de onde o Friends Reels vai importar/acompanhar Reels.

Devem existir dois modos conceptuais de seleção.

### Modo 1 — Apenas selecionados

O utilizador escolhe explicitamente as pessoas e/ou grupos/conversas que quer incluir.

Neste modo, apenas essas fontes são consideradas.

### Modo 2 — Excluir selecionados

O utilizador escolhe pessoas e/ou grupos/conversas que não quer incluir.

Neste modo, as restantes conversas são aceites.

A nomenclatura final destes dois modos pode ser escolhida de forma simples e natural, idealmente próxima da linguagem habitual do Instagram.

### Requisitos da seleção

Deve ser possível:

- selecionar pessoas individualmente;
- selecionar grupos;
- pesquisar pelo nome da pessoa/conversa/grupo;
- selecionar e desselecionar várias fontes;
- alterar a seleção posteriormente;
- deixar claro que fontes estão atualmente incluídas/excluídas.

Num grupo, o grupo funciona como uma fonte de Reels, mas cada Reel deve continuar a identificar corretamente **qual foi a pessoa que o enviou**.

---

## 9. Importação e descoberta de Reels antigos

Este é um requisito essencial.

O projeto deve procurar uma forma de descobrir Reels que já existem nas conversas do Instagram, incluindo Reels antigos.

O utilizador não deve ser obrigado a reenviar centenas de Reels manualmente para uma aplicação externa apenas para que possam ser adicionados ao Friends Reels.

A solução deve procurar conseguir, quando tecnicamente possível:

- descobrir as conversas relevantes;
- aplicar as regras de seleção configuradas pelo utilizador;
- analisar as mensagens dessas conversas;
- identificar mensagens que contenham Reels;
- associar corretamente cada Reel à conversa e ao remetente;
- guardar uma referência ao Reel e à mensagem original;
- evitar duplicados;
- atualizar a biblioteca com novos Reels.

Devem ser considerados tanto históricos antigos como novos Reels que sejam recebidos depois.

---

## 10. Dados e organização interna

Cada Reel deve manter, quando disponível, metadados suficientes para preservar a ligação entre o feed do Friends Reels e a mensagem original no Instagram.

Exemplos de campos relevantes:

- `reel_url`
- `sender_id`
- `sender_name`
- `conversation_id`
- `conversation_name`
- `original_message_id`
- `sent_at`
- `imported_at`
- `viewed_at`
- `replied_at`
- `reaction_sent`
- `reaction_current`
- `reply_text`
- `reply_status`
- `import_status`

A estrutura exata pode ser alterada durante a implementação, desde que preserve os requisitos funcionais.

A reação e as respostas devem continuar conceptualmente ligadas à mensagem original do Reel.

---

## 11. Definições e configurações

Deve existir uma forma de aceder às configurações do Friends Reels.

### Configurações principais

As configurações devem permitir, conforme aquilo que a solução final tornar tecnicamente possível:

- escolher entre modo **Apenas selecionados** e modo **Excluir selecionados**;
- selecionar pessoas;
- selecionar grupos/conversas;
- definir se grupos são incluídos;
- ativar/desativar a importação de histórico antigo;
- definir opções relacionadas com sincronização/atualização;
- inverter a direção dos swipes;
- outras configurações necessárias para o funcionamento da solução escolhida.

A configuração dos Reels deve ser claramente separada de ações que pertençam ao próprio Instagram.

### Relação entre Instagram e aplicação de configurações

Se a solução principal utilizar a aplicação externa apenas para configurações ou para funções auxiliares, deve ser possível, a partir dos **3 pontinhos** do Friends Reels, abrir diretamente essa aplicação para alterar o que for necessário.

Quando forem alteradas definições, a experiência do Friends Reels deve refletir as alterações ao regressar ao Instagram.

Se for necessário um botão de confirmação/atualização para sincronizar as mudanças, isso é aceitável.

Da mesma forma, a aplicação externa deve ter uma ação para **abrir o Instagram diretamente no Friends Reels**, quando isso for suportado pela solução implementada.

---

## 12. Menu dos 3 pontinhos

A experiência deve possuir um menu de **3 pontinhos**, mesmo no cenário de aplicação externa, para manter uma experiência consistente e dar acesso às ações contextuais.

No cenário de integração dentro do Instagram, esse menu deve poder incluir uma opção para abrir as configurações externas quando isso for necessário.

No cenário de aplicação externa, devem existir ações equivalentes adequadas ao contexto, incluindo, quando aplicável:

- **Abrir Reel no Instagram**;
- **Abrir conversa no Instagram**;
- abrir definições;
- outras ações que sejam úteis e que não criem duplicação desnecessária das funcionalidades nativas do Instagram.

---

## 13. Comportamento quando a solução for uma aplicação externa

A aplicação externa só deve ser utilizada como solução principal depois de ser demonstrado que a integração dentro do Instagram não consegue cumprir os requisitos essenciais.

Nesse cenário, o feed deve continuar a proporcionar a experiência Friends Reels pretendida.

### Abrir o Reel no Instagram

Deve existir uma ação que abra o Reel **na experiência normal de Reels do Instagram**, e não apenas a aplicação do Instagram de forma genérica.

O objetivo é permitir ao utilizador fazer normalmente ações que pertençam ao Instagram, incluindo, por exemplo:

- dar Like;
- comentar;
- guardar;
- partilhar;
- utilizar outras ações nativas do Reel.

### Abrir a conversa no Instagram

Deve existir uma ação separada para abrir o Instagram **diretamente na conversa original do Reel**.

Esta ação é importante quando o utilizador quer consultar a conversa ou executar manualmente uma ação relacionada com a DM.

### Guardar em listas do Instagram

É desejável, como bónus, que a aplicação externa consiga permitir ao utilizador guardar o Reel utilizando as listas de guardados que já existem no Instagram, incluindo a seleção dessas listas, caso isso possa ser feito de forma correta.

Se não for possível, deve existir uma indicação clara para usar a ação de abrir o Reel no Instagram e fazer o guardado diretamente lá.

### Responder quando a app externa não consegue enviar diretamente

Se não for possível enviar uma resposta para a DM através da aplicação externa:

- não deve existir um campo de texto que pareça enviar a resposta;
- deve existir uma indicação clara de que a resposta tem de ser feita no Instagram;
- deve existir um botão que abra diretamente a conversa original, no contexto do Reel, para o utilizador responder manualmente.

---

## 14. Integração visual e comportamental

A interface deve ser suficientemente semelhante à experiência de Reels do Instagram para ser intuitiva, mas **não deve copiar indiscriminadamente todas as ações existentes no Reel normal**.

O Friends Reels é uma experiência específica para Reels recebidos por amigos.

Por isso, dentro do feed Friends Reels, as ações principais devem ser as relacionadas com:

- navegar para o Reel seguinte/anterior;
- reagir ao Reel;
- responder ao Reel;
- consultar respostas anteriores;
- abrir o Reel no Instagram;
- abrir a conversa no Instagram;
- outras ações específicas desta funcionalidade.

A ação normal de **Like** e os comentários públicos do Reel não fazem parte da experiência principal do Friends Reels.

Quando o utilizador quiser executar essas ações normais do Instagram, deve poder fazê-lo através da opção apropriada para abrir o Reel no Instagram.

---

## 15. Segurança, privacidade e acesso a dados

A implementação deve minimizar os dados guardados e tratados.

Requisitos importantes:

- não pedir ou guardar a password do Instagram diretamente na aplicação;
- usar mecanismos oficiais de autenticação quando forem adequados e suportados;
- limitar o acesso aos dados às conversas selecionadas pelo utilizador;
- explicar claramente que dados são necessários e para quê;
- preferir armazenamento local quando não for necessário um servidor;
- evitar enviar dados das DMs para um servidor sem necessidade real;
- guardar referências/IDs/URLs quando forem suficientes, em vez de descarregar permanentemente os vídeos;
- permitir apagar a biblioteca importada e os dados associados.

Caso sejam utilizados mecanismos Android de automação, o projeto deve descrever claramente o que fazem, quais são as permissões necessárias e quais as limitações técnicas encontradas.

---

## 16. Plano de investigação e testes de viabilidade

A investigação de viabilidade deve ser **orientada pelos objetivos**, e não por uma lista fechada de tecnologias escolhidas antecipadamente.

A outra AI deve investigar as abordagens que considerar relevantes e robustas para cada requisito.

Não deve existir uma decisão prévia do género “usar obrigatoriamente API X” ou “usar obrigatoriamente AccessibilityService”.

O objetivo é descobrir experimentalmente qual a melhor combinação de mecanismos para atingir os requisitos.

### Teste de viabilidade principal

Deve ser criado um conjunto de testes capaz de responder, pelo menos, às seguintes perguntas:

- É possível descobrir as conversas relevantes?
- É possível selecionar as conversas/pessoas/grupos que devem ser analisados?
- É possível encontrar os Reels dentro do histórico?
- É possível associar cada Reel ao remetente correto?
- É possível manter a referência à mensagem original?
- É possível mostrar os Reels num feed próprio?
- É possível identificar se o utilizador já viu o Reel?
- É possível ler a reação atual associada ao Reel?
- É possível enviar/alterar uma reação na mensagem original?
- É possível responder ao Reel de forma equivalente ao comportamento nativo de resposta de uma DM?
- É possível abrir diretamente o Reel correto no Instagram?
- É possível abrir diretamente a conversa correta no Instagram?
- É possível manter o fluxo de utilização sem perder o contexto?

### Matriz de resultados

A investigação deve produzir uma matriz comparativa baseada nos resultados reais dos testes.

Exemplo de estrutura:

| Capacidade | Abordagem 1 | Abordagem 2 | Abordagem 3 | Melhor resultado |
|---|---|---|---|---|
| Descobrir conversas | | | | |
| Encontrar Reels | | | | |
| Identificar remetente | | | | |
| Associar mensagem original | | | | |
| Ler reação atual | | | | |
| Enviar reação | | | | |
| Enviar resposta | | | | |
| Abrir Reel específico | | | | |
| Abrir conversa específica | | | | |
| Manter contexto | | | | |

Os resultados devem ser classificados de forma objetiva, por exemplo como:

- **POSSÍVEL** — foi demonstrado num teste;
- **LIMITADO** — funciona apenas em determinadas condições ou requer interação adicional;
- **NÃO DISPONÍVEL** — não foi possível demonstrar a funcionalidade com a abordagem testada.

A escolha da arquitetura deve ser feita **depois desta análise**, com base no que foi realmente demonstrado.

A AI deve explicar porque escolheu uma abordagem ou combinação de abordagens e que requisitos essa escolha consegue cumprir.

### Proof of Concept

Antes de desenvolver a aplicação completa, deve existir um pequeno Proof of Concept que demonstre as partes críticas da solução escolhida.

O âmbito exato do PoC deve ser definido pela própria AI com base na investigação anterior. Não é obrigatório seguir uma implementação pré-determinada.

### Teste de histórico e escala

Depois de existir uma solução funcional para uma pequena quantidade de dados, deve ser testada a descoberta/importação de volumes crescentes de Reels, por exemplo:

- 10 Reels;
- 100 Reels;
- 500+ Reels.

Devem ser avaliados, quando relevantes:

- duplicados;
- falhas;
- perdas de dados;
- identificação incorreta de remetente/conversa;
- capacidade de retomar uma importação interrompida;
- consistência dos estados.

### Teste de UX

A UX deve ser avaliada pelo resultado pretendido, sem obrigar antecipadamente a uma implementação específica.

Devem ser observadas, pelo menos, estas ações:

- ver o próximo Reel;
- voltar ao anterior;
- reagir;
- responder;
- consultar uma resposta anterior;
- abrir o Reel no Instagram;
- abrir a conversa no Instagram;
- regressar e continuar no feed.

A experiência deve procurar reduzir o número de passos e manter o contexto do Reel sempre que tecnicamente possível.

---

## 17. Critérios de sucesso

O projeto deve ser considerado funcionalmente bem-sucedido quando conseguir demonstrar, numa conta de teste, o máximo possível dos seguintes requisitos:

1. Descobrir/importar Reels antigos recebidos em DMs sem exigir o reenvio manual de cada Reel.
2. Permitir selecionar apenas determinadas pessoas/conversas/grupos.
3. Permitir excluir determinadas pessoas/conversas/grupos e aceitar as restantes.
4. Identificar corretamente o remetente e a conversa de cada Reel.
5. Mostrar os Reels num feed vertical.
6. Marcar Reels como vistos.
7. Manter o estado Reagido de acordo com a reação atualmente existente.
8. Permitir reagir ao Reel no contexto original da DM, quando tecnicamente possível.
9. Permitir responder ao Reel no contexto original da DM, com comportamento equivalente ao Reply nativo do Instagram, quando tecnicamente possível.
10. Permitir consultar respostas anteriores e enviar uma nova resposta quando essa funcionalidade estiver disponível.
11. Abrir o Reel correto no Instagram quando for necessária uma ação nativa como Like, comentário ou guardar.
12. Abrir diretamente a conversa correta no Instagram quando for necessária uma ação manual na DM.
13. Não exigir guardar a password do Instagram na aplicação.
14. Priorizar a integração dentro do Instagram antes de recorrer à aplicação externa.

Nenhum critério deve ser considerado “cumprido” apenas porque existe uma demonstração teórica. As capacidades críticas devem ser testadas.

---

## 18. Ordem recomendada de desenvolvimento

A ordem de desenvolvimento deve refletir a prioridade das soluções e os riscos técnicos.

### Fase 0 — Investigação de viabilidade

Determinar como cada requisito pode ou não ser concretizado e qual é a melhor arquitetura.

A investigação deve priorizar a Opção A e só avançar para A.2/B e C conforme os resultados demonstrem a necessidade.

### Fase 1 — Proof of Concept

Implementar apenas o necessário para demonstrar as partes críticas da abordagem escolhida.

### Fase 2 — Fundação do Friends Reels

Criar a estrutura de dados, descoberta/importação e feed vertical com base na abordagem validada.

### Fase 3 — Seleção de fontes

Implementar o controlo sobre pessoas, grupos e conversas, incluindo os dois modos de seleção.

### Fase 4 — Estados e ações

Implementar vistos, reações, respostas e a ligação com a mensagem original, conforme permitido pela solução validada.

### Fase 5 — Integração e navegação

Implementar a abertura contextual do Reel e da conversa no Instagram, bem como a ligação às definições externas quando necessário.

### Fase 6 — Histórico e robustez

Testar volumes maiores, duplicados, falhas, sincronização e consistência dos dados.

### Fase 7 — Segurança e privacidade

Rever permissões, armazenamento, tratamento dos dados e comportamentos relacionados com acesso a DMs.

### Fase 8 — Validação final

Verificar o comportamento de ponta a ponta e confirmar que a solução final segue a ordem de prioridade definida neste documento.

---

## 19. Ficheiro de progresso do projeto

Desde o início do desenvolvimento, deve ser criado e mantido um ficheiro separado para acompanhar todo o progresso do projeto, por exemplo:

`PROJECT_PROGRESS.md`

Esse ficheiro deve reunir, de forma cumulativa, o conhecimento e o desenvolvimento do projeto para evitar perda de contexto e facilitar o acompanhamento.

Deve incluir, pelo menos:

- requisitos identificados;
- investigação realizada;
- abordagens testadas;
- resultados dos testes;
- decisões técnicas tomadas;
- razões para cada decisão importante;
- limitações descobertas;
- funcionalidades implementadas;
- funcionalidades ainda pendentes;
- problemas encontrados;
- alterações de arquitetura;
- estado atual do projeto;
- próximos passos concretos.

Sempre que uma decisão importante for alterada, o ficheiro deve ser atualizado para manter o histórico de raciocínio e desenvolvimento.

---

## 20. Instrução principal para a AI que vai desenvolver o projeto

**Estás a começar este projeto do zero.**

O teu trabalho é investigar, testar, decidir e implementar a melhor solução para os requisitos deste documento.

O requisito prioritário é conseguir realizar o Friends Reels **dentro da própria aplicação do Instagram**, idealmente como uma nova opção na barra inferior do Instagram. Esta é a **Opção A**.

Caso a Opção A não seja possível, deves investigar uma alternativa que continue a manter a experiência dentro da aplicação do Instagram, como uma opção/botão integrada noutro ponto relevante. Esta é a **Opção A.2/B**.

Só quando os testes demonstrarem que essas abordagens não conseguem cumprir os requisitos essenciais é que deves recorrer à **Opção C, com uma aplicação externa**.

Não escolhas a aplicação externa apenas por ser mais simples de desenvolver.

Também não assumes que uma determinada API, mecanismo de automação ou técnica é a resposta só porque parece adequada teoricamente. **Testa primeiro.**

A investigação deve ser orientada pelos requisitos e pelos resultados experimentais. Podes escolher as tecnologias e métodos que considerares mais adequados e podes combinar abordagens quando isso produzir um resultado melhor.

As funcionalidades críticas, especialmente descoberta de Reels em DMs, associação à mensagem original, reação e resposta, devem ser validadas através de provas reais antes de serem tratadas como suportadas.

Quando uma ação não puder ser executada diretamente pelo Friends Reels, a implementação deve disponibilizar o fallback correto previsto neste documento em vez de simular uma ação que na realidade não foi enviada.

Não gastes tempo a estimar quantos dias, semanas ou meses serão necessários, nem quantas pessoas seriam necessárias para desenvolver o projeto. O objetivo é **fazer o trabalho** e manter um registo claro do que foi descoberto e implementado.

Desde o início, cria e mantém o ficheiro `PROJECT_PROGRESS.md` para registar todo o conhecimento, investigação, decisões, testes, resultados, implementação e estado do projeto.

Atualiza esse ficheiro continuamente para que nenhuma etapa importante fique perdida e para que seja possível compreender claramente, a qualquer momento, o estado do projeto e o caminho que levou às decisões atuais.

Não comeces por construir uma UI completa sem primeiro compreender as limitações e possibilidades técnicas das partes críticas. Primeiro valida a abordagem; depois desenvolve a aplicação de forma progressiva.

A prioridade final é:

**A → A.2/B → C**

E a passagem de uma opção para a seguinte deve acontecer com base nos resultados dos testes e não simplesmente por conveniência de implementação.
