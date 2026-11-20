# Abastecimento GCM Serviços

Aplicativo Android para o controle de abastecimento dos veículos da GCM Serviços.

- O técnico fotografa o painel no início (8h) e no fim do dia (17h). O app lê o km automaticamente (Google ML Kit, no próprio celular) e envia para a planilha.
- No abastecimento, o técnico fotografa a nota, confere o valor (padrão R$ 180,00), os litros e os km que couberam no tanque (litros x 10 km/l).
- A planilha calcula os km restantes. Quando faltarem 20 km, o responsável recebe um e-mail informando qual técnico precisa abastecer.
- A aba "Resumo diário" mostra os km rodados por técnico em cada dia.

## Peças do sistema

| Parte | Onde fica |
|---|---|
| Planilha (dados e cálculos) | Google Sheets "Controle de Abastecimento GCM Serviços" |
| Servidor (registro, fotos e e-mails) | `apps-script/Codigo.gs`, instalado na planilha |
| Aplicativo | `app/` (Kotlin, Jetpack Compose) |

## 1. Instalar o script na planilha (uma vez)

1. Abra a planilha e vá em **Extensões > Apps Script**.
2. Apague o conteúdo do editor, cole o arquivo `apps-script/Codigo.gs` e salve.
3. Em **Configurações do projeto** (ícone de engrenagem) > **Propriedades do script**, adicione `CHAVE_APP` com uma senha de sua escolha.
4. Clique em **Implantar > Nova implantação**, tipo **App da Web**. Executar como: **Eu**. Quem pode acessar: **Qualquer pessoa**.
5. Autorize o acesso quando o Google pedir e copie a **URL do app da Web**.

## 2. Instalar o aplicativo nos celulares

1. No celular, abra a página de versões do repositório e baixe `abastecimento-gcm.apk` da versão mais recente:
   https://github.com/glaydsonmj-ops/Abastecimento_Repositorio_GCM/releases/latest
2. Abra o arquivo e permita a instalação de apps desta fonte, se o Android pedir.
3. No primeiro uso, informe a URL do app da Web e a chave (`CHAVE_APP`), escolha o técnico e o veículo.
4. Permita as notificações para receber os lembretes das 8h e das 17h.

## Cadastros

- **Veículos:** placa, modelo, ano e cor, na aba Veículos. As colunas cinza são calculadas.
- **Técnicos:** nome, e-mail, placa do veículo e "Sim" em Ativo, na aba Técnicos.
- **Parâmetros:** consumo, antecedência do alerta, e-mail do responsável, valor padrão e horários dos lembretes, na aba Configurações.

## Gerar uma nova versão

Cada envio para a branch `main` gera o APK automaticamente (GitHub Actions) e publica uma nova versão para download. O APK é assinado com a chave em `assinatura/`, para que a nova versão instale por cima da anterior. Para usar uma chave privada, cadastre os segredos `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD` e `KEY_ALIAS` no repositório; observe que a troca de chave exige desinstalar a versão antiga uma vez.
