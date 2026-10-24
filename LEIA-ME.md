# Launcher do Carro

Ecrã inicial minimalista para o rádio Android, com dois layouts:
**Painel** (foto num cartão, relógio, multimédia e 6 botões) e **Foto em destaque**
(foto em ecrã inteiro com uma barra flutuante).

## 1. Gerar o APK no computador

1. Descompacte esta pasta.
2. No Android Studio: **File → Open** e escolha a pasta `CarLauncher`.
3. Espere que a sincronização acabe (barra em baixo). Da primeira vez descarrega o Gradle,
   por isso precisa de internet e pode demorar alguns minutos.
   Se aparecer um aviso a sugerir atualizar o "Android Gradle Plugin", pode aceitar.
4. Menu **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
5. Quando acabar, clique em **locate** no aviso. O ficheiro é
   `app/build/outputs/apk/debug/app-debug.apk`.

## 2. Instalar no rádio

1. Copie o `app-debug.apk` para uma pen USB e ligue-a ao rádio.
2. Abra o **Filemanager** do rádio, encontre o ficheiro e toque nele para instalar.
   Se pedir, permita a instalação de "fontes desconhecidas".
3. Carregue no botão de casa (⌂). Escolha **Launcher do Carro** e toque em **Sempre**.
   Se não perguntar: Definições → Aplicações e notificações → Avançadas →
   Aplicações predefinidas → Aplicação de página inicial.
4. Na primeira abertura, aceite a permissão de localização (é o que mostra os km/h).

## 3. Usar

| Ação | O que faz |
|---|---|
| Toque longo na foto / no fundo | Escolher a foto do carro (JPG ou PNG) |
| Toque num botão | Abre a app desse botão |
| Toque longo num botão | Escolher que app esse botão abre |
| Toque longo no relógio ou em Apps | Definições: foto, layout, cor, apps |
| Botão Apps | Todas as apps instaladas |

Os botões tentam encontrar sozinhos as apps do rádio (Rádio, Bluetooth, ZLink, etc.)
pelo nome. O que não acertar, corrige com um toque longo.

## 4. Ecrã "A tocar" (versão 2)

Toque no cartão de multimédia do ecrã inicial para abrir o ecrã "A tocar".
Mostra a música, o artista, a capa e o progresso do que estiver a tocar,
incluindo o Spotify do telemóvel por Bluetooth.

- **Da primeira vez** aparece "Falta uma autorização". Toque em **Ativar acesso** e ligue o
  **Launcher do Carro** na lista de "acesso a notificações". O Android exige isto para uma app
  poder ver a música que está a tocar. A app não lê as suas notificações.
- **Capa do álbum:** como o rádio não a recebe por Bluetooth, a app vai buscá-la à internet
  (primeiro ao Deezer, depois ao iTunes), e só a mostra se o artista e a música baterem certo.
  Para isso, o rádio tem de estar ligado ao Wi-Fi (por exemplo, o hotspot do telemóvel).
  Cada capa é guardada no rádio e só é descarregada uma vez.
- O botão **Música** da barra também abre o ecrã "A tocar".
- A etiqueta "Telemóveis ›" no canto do ecrã "A tocar" abre a app de Bluetooth do rádio
  (a mesma do botão Telefone), para gerir os telemóveis.
- **Toque longo no cartão de multimédia** abre a app de música escolhida para o botão Música.
- Se a música não aparecer: Definições do launcher → **Acesso a notificações** tem de estar "Ativo".

Para atualizar da versão 1 basta instalar o novo APK por cima. As definições mantêm-se.

## 5. Novidades da versão 2.4

- **Capa pequena no ecrã inicial**, ao lado do nome da música (nos dois layouts).
- **Cor a acompanhar a capa:** o botão play, a barra de progresso e as etiquetas mudam
  suavemente para a cor de cada álbum. Capas sem cor (preto e branco) usam a cor escolhida.
  Desliga-se nas definições do launcher.
- **Tempo ao lado da data:** ícone e temperatura do sítio onde está o carro (Open-Meteo,
  gratuito). Precisa de internet e de o GPS ter apanhado sinal pelo menos uma vez.
  Atualiza de 30 em 30 minutos e esconde-se se o valor tiver mais de 3 horas.

## 6. Modo noite (versão 2.5)

Do pôr ao nascer do sol, o ecrã escurece suavemente e a foto do carro ainda um pouco mais.
As horas do sol são calculadas pela posição do GPS e pela data, **sem internet**.
Antes de o GPS apanhar sinal pela primeira vez, usa Portugal como referência.

Para testar de dia: definições do launcher → **Modo noite** → "Sempre ligado".
Depois volte a pôr em "Automático".

## 7. Rádio online (versão 2.6)

O botão **Rádio** abre um ecrã com 8 rádios: RFM, Comercial, M80, Cidade FM, Renascença,
Antena 1, Antena 3 e TSF. Um toque numa rádio põe-na a tocar; outro toque para.

- Precisa de internet (hotspot). Continua a tocar mesmo que saia do ecrã.
- A rádio aparece no ecrã "A tocar" e no cartão do ecrã inicial. ⏮ ⏭ mudam de rádio,
  incluindo pelos botões do volante.
- Se puser música no Spotify, a rádio para sozinha, e vice-versa.
- Cada rádio tem ligações de reserva. Se uma mostrar "SEM LIGAÇÃO" com a internet a funcionar,
  a ligação dessa rádio mudou: as ligações estão em `RadioStations.java`.

## 8. Viagem e animação de arranque (versão 2.8)

**Dados da viagem.** Por baixo dos km/h (ou no canto da foto, no layout Painel) aparece
"VIAGEM" com os km, o tempo e a velocidade média, calculados pelo GPS.
- Continua a contar com outras apps abertas (Waze, Spotify…).
- Quando o carro fica desligado mais de 10 minutos, começa uma viagem nova e a anterior fica guardada.
- Toque na viagem para abrir o ecrã **Viagens** (versão 2.9): a viagem atual e as últimas 10,
  com data, distância, tempo e média de cada uma, e os totais por cima.
  Lá também está o botão "Começar nova viagem".
- Para isto funcionar em segundo plano, o Android mostra uma notificação discreta "A registar a viagem".

**Animação de arranque.** Ao ligar o carro aparece a foto em ecrã inteiro com
"Bom dia", "Boa tarde" ou "Boa noite", a data e a temperatura. Um toque salta-a.
- Para ver sem desligar o carro: definições do launcher → "Ver a animação de arranque agora".
- Desliga-se nas definições.

## 9. Versão 3.0

- **Ecrã Carro:** toque na linha "VIAGEM" do ecrã inicial. Mostra a viagem atual em grande,
  o cartão do motor (para o OBD2, em breve) e as últimas 10 viagens com os totais.
- **Janelas com o design do launcher:** definições (agora por secções, com o estado de cada opção
  à direita), escolher apps, cores, modo noite e confirmações deixaram de usar as janelas
  cinzentas do Android.

## 10. Dados do carro (versão 3.1)

O rádio tem uma caixa CAN que lê dados do Golf (combustível, temperatura exterior, bateria,
km totais, portas…), visíveis na app CanSetting do rádio.
- No ecrã Carro, o cartão **"Dados do carro ›"** abre essa app. Toque longo para escolher outra.
- Definições do launcher → **Enviar a app do carro para o PC**: com o PC no mesmo Wi-Fi,
  abre-se o endereço mostrado no browser e descarregam-se as apps CanSetting para análise
  (só leitura; desliga-se ao fim de 15 minutos).

## 11. Rádio e telemóvel separados (versão 3.5)

- **Ecrã "A tocar"** = só a música do telemóvel (Bluetooth).
- **Ecrã "Rádio"** = só a rádio online.
- O cartão do ecrã inicial mostra a rádio quando ela está a tocar (e os botões controlam a rádio);
  no resto do tempo mostra a música do telemóvel.
- **Logótipos das rádios:** pôr imagens quadradas (PNG ou JPG) em `app/src/main/res/drawable-nodpi/`
  com os nomes `radio_rfm`, `radio_comercial`, `radio_m80`, `radio_cidade`, `radio_renascenca`,
  `radio_megahits`, `radio_antena1`, `radio_antena3`, `radio_tsf`. Sem logótipo, aparece a cor e o nome.

## 12. Ecrã "Rádio a tocar" (versão 3.8)

Tocar numa rádio abre o ecrã "Rádio a tocar": logótipo grande, "AO VIVO" a piscar,
barras animadas na cor da rádio, botões ⏮ ⏯ ⏭ e, em baixo, todas as rádios para mudar com um toque.
"Todas as rádios ›" volta à grelha. As barras são uma animação (não leem o som).

## 13. Botão Carro (versão 3.9)

O botão "Android Auto" da barra passou a ser **Carro**: abre o ecrã das viagens e dos dados do carro,
nos dois layouts. A linha "VIAGEM" do ecrã inicial foi retirada.

## 14. Atualizações pela internet (GitHub)

Cada vez que o código muda no GitHub, o GitHub cria o APK sozinho (pasta `.github/workflows`)
e publica-o em **Releases**. No rádio: Definições do launcher → **Procurar atualizações**
(e a app também verifica sozinha ao ligar o carro, no máximo de 6 em 6 horas).

### Preparar (só uma vez)

1. **Criar o repositório** no GitHub, **público** (para o rádio conseguir descarregar sem senha),
   por exemplo `CarLauncher`, e enviar para lá esta pasta (GitHub Desktop: "Add existing repository").
2. **Chave de assinatura.** O Android só aceita uma atualização assinada com a mesma chave do APK
   instalado. Os APKs feitos no Android Studio usam a chave de debug do seu computador, por isso
   usa-se essa mesma chave no GitHub:
   - No Windows, abra o **PowerShell** e copie a chave em texto:
     `[Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\.android\debug.keystore")) | Set-Clipboard`
   - No GitHub: repositório → **Settings → Secrets and variables → Actions → New repository secret**:
     - `KEYSTORE_BASE64` = (colar o texto copiado)
     - `KEYSTORE_PASSWORD` = `android`
     - `KEY_ALIAS` = `androiddebugkey`
     - `KEY_PASSWORD` = `android`
3. **Primeira versão:** em **Actions → Criar APK → Run workflow**. Quando acabar, aparece em **Releases**.
4. **Instalar essa versão uma vez à mão** no rádio (abrir a página Releases no navegador do rádio e
   descarregar `CarLauncher.apk`, ou passar pelo Drive). A partir daí, as atualizações são pelo botão.

### Depois

Mudar o código → enviar para o GitHub (commit + push) → esperar ~5 minutos →
no rádio, **Procurar atualizações → Atualizar → Instalar**. A mensagem do commit aparece como
"novidades" da versão.

## Notas

- A barra de cima do sistema fica escondida nos ecrãs do launcher. Para a ver por uns segundos,
  deslize o dedo do topo do ecrã para baixo. Para a mostrar sempre:
  definições do launcher → "Mostrar a barra de cima do sistema".

- A foto do Golf já vem incluída na app. Para usar outra, faça toque longo na foto.

- O design está pensado para ecrãs 1024×600, mas ajusta-se a qualquer resolução.
- Os km/h só aparecem se o rádio tiver antena GPS ligada.
- Os botões ⏮ ⏯ ⏭ controlam a app de música ou rádio que estiver a tocar.
- Letra: Rajdhani (licença SIL Open Font, em `app/src/main/assets/fonts/OFL.txt`).

## 15. Motor (adaptador OBD2)

Adaptador: Vgate iCar Pro **Bluetooth 3.0** (Bluetooth clássico; o rádio não tem BLE).
- Primeira vez: ecrã Carro → cartão **MOTOR ›** (ou Definições → Adaptador OBD2) →
  "Procurar adaptador" com a ignição ligada → tocar no adaptador (OBDII / V-LINK / iCar). Código: 1234.
- Depois liga-se sozinho quando o carro arranca (tenta de 5 em 5 s, depois com mais calma).
- Mostra rotações, velocidade, temperatura do motor, turbo, bateria, consumo (estimativa) e a luz
  do motor com os códigos de erro explicados. "Apagar erros" só depois de reparar.
- O consumo de cada viagem aparece na lista de viagens. Acertar: Motor → Mais → Acertar o consumo.
- Se algo falhar: Motor → Mais → Registo da ligação (ou "Enviar a app do carro para o PC" → obd.txt).
