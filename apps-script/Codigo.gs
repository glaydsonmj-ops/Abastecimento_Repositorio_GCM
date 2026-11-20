/**
 * Controle de Abastecimento GCM Serviços - Google Apps Script
 *
 * Instalação (uma única vez):
 * 1. Abra a planilha "Controle de Abastecimento GCM Serviços".
 * 2. Menu Extensões > Apps Script. Apague o conteúdo e cole este arquivo.
 * 3. Em Configurações do projeto > Propriedades do script, crie a propriedade
 *    CHAVE_APP com uma senha qualquer (o app usará a mesma senha).
 * 4. Implantar > Nova implantação > Tipo "App da Web".
 *    Executar como: Eu. Quem pode acessar: Qualquer pessoa.
 * 5. Copie a URL gerada; ela será configurada no aplicativo.
 */

const ABA = {
  VEICULOS: 'Veículos',
  TECNICOS: 'Técnicos',
  LEITURAS: 'Leituras',
  ABASTECIMENTOS: 'Abastecimentos',
  ALERTAS: 'Alertas',
  CONFIG: 'Configurações',
};
const PASTA_FOTOS = 'Fotos Abastecimento GCM';

/** GET: devolve veículos e técnicos ativos para o aplicativo. */
function doGet(e) {
  if (!chaveValida_(e.parameter.chave)) return json_({ ok: false, erro: 'Chave inválida' });
  const ss = SpreadsheetApp.getActive();
  const veiculos = linhas_(ss.getSheetByName(ABA.VEICULOS))
    .filter(r => r[0])
    .map(r => ({ placa: r[0], modelo: r[1], ano: r[2], cor: r[3], kmAtual: r[6], kmRestantes: r[8], situacao: r[10] }));
  const tecnicos = linhas_(ss.getSheetByName(ABA.TECNICOS))
    .filter(r => r[0] && String(r[3]).toLowerCase() !== 'não')
    .map(r => ({ nome: r[0], email: r[1], placa: r[2] }));
  const cfg = config_().getRange('B2:B7').getDisplayValues().map(v => v[0]);
  const config = { consumoKmL: Number(cfg[0]) || 10, antecedenciaKm: Number(cfg[1]) || 20,
                   valorPadrao: 180, lembreteInicio: cfg[4] || '08:00', lembreteFim: cfg[5] || '17:00' };
  config.valorPadrao = Number(config_().getRange('B5').getValue()) || 180;
  return json_({ ok: true, veiculos, tecnicos, config });
}

/**
 * POST: registra uma leitura ou um abastecimento.
 * Corpo JSON: { chave, tipo: "leitura"|"abastecimento", tecnico, placa, km,
 *   leitura:       periodo ("Início do dia"|"Fim do dia"), origem, fotoBase64 (painel)
 *   abastecimento: valor, litros, kmCoube, fotoBase64 (nota fiscal) }
 * Se kmCoube não vier, é estimado como litros x consumo médio.
 */
function doPost(e) {
  const lock = LockService.getScriptLock();
  lock.waitLock(30000);
  try {
    const d = JSON.parse(e.postData.contents);
    if (!chaveValida_(d.chave)) return json_({ ok: false, erro: 'Chave inválida' });
    const km = Number(d.km);
    if (!d.placa || !d.tecnico || !(km > 0)) return json_({ ok: false, erro: 'Dados incompletos' });

    const ss = SpreadsheetApp.getActive();
    const agora = new Date();
    const foto = d.fotoBase64 ? salvarFoto_(d.fotoBase64, d.placa, agora) : '';

    if (d.tipo === 'abastecimento') {
      const consumo = Number(config_().getRange('B2').getValue()) || 10;
      const litros = Number(d.litros) || '';
      const kmCoube = Number(d.kmCoube) || (litros ? Math.round(litros * consumo) : '');
      if (!kmCoube) return json_({ ok: false, erro: 'Informe os km que couberam no tanque ou os litros' });
      ss.getSheetByName(ABA.ABASTECIMENTOS).appendRow([agora, d.tecnico, d.placa, km, Number(d.valor) || '', litros, kmCoube, foto]);
    } else {
      ss.getSheetByName(ABA.LEITURAS).appendRow([agora, d.tecnico, d.placa, km, d.periodo || '', foto, d.origem || 'Leitura automática']);
    }
    SpreadsheetApp.flush();

    const status = situacaoVeiculo_(d.placa);
    let alertaEnviado = false;
    if (status && status.situacao === 'Abastecer' && d.tipo !== 'abastecimento') {
      alertaEnviado = enviarAlerta_(status, d.tecnico, agora);
    }
    return json_({ ok: true, situacao: status, alertaEnviado, kmDoDia: kmDoDia_(d.tecnico, d.placa, agora) });
  } catch (err) {
    return json_({ ok: false, erro: String(err) });
  } finally {
    lock.releaseLock();
  }
}

function situacaoVeiculo_(placa) {
  const r = linhas_(SpreadsheetApp.getActive().getSheetByName(ABA.VEICULOS)).find(l => l[0] === placa);
  if (!r) return null;
  return { placa: r[0], modelo: r[1], cor: r[3], kmCoube: r[4], kmUltimoAbastecimento: r[5],
           kmAtual: r[6], kmRodados: r[7], kmRestantes: r[8], litrosRestantes: r[9], situacao: r[10] };
}

/** Km rodados no dia pelo técnico naquele veículo (maior leitura menos a menor). */
function kmDoDia_(tecnico, placa, agora) {
  const dia = Utilities.formatDate(agora, 'America/Sao_Paulo', 'yyyy-MM-dd');
  const kms = linhas_(SpreadsheetApp.getActive().getSheetByName(ABA.LEITURAS))
    .filter(r => r[1] === tecnico && r[2] === placa &&
                 Utilities.formatDate(new Date(r[0]), 'America/Sao_Paulo', 'yyyy-MM-dd') === dia)
    .map(r => Number(r[3]));
  return kms.length ? Math.max(...kms) - Math.min(...kms) : 0;
}

function config_() {
  return SpreadsheetApp.getActive().getSheetByName(ABA.CONFIG);
}

/** Envia o alerta ao responsável uma única vez por ciclo de abastecimento. */
function enviarAlerta_(s, nomeTecnico, agora) {
  const ss = SpreadsheetApp.getActive();
  const ultimoAbastecimento = linhas_(ss.getSheetByName(ABA.ABASTECIMENTOS))
    .filter(r => r[2] === s.placa).map(r => new Date(r[0]).getTime());
  const desde = ultimoAbastecimento.length ? Math.max(...ultimoAbastecimento) : 0;
  const jaAvisado = linhas_(ss.getSheetByName(ABA.ALERTAS))
    .some(r => r[1] === s.placa && new Date(r[0]).getTime() > desde);
  if (jaAvisado) return false;

  const destinos = String(config_().getRange('B4').getValue()).trim();
  if (!destinos) return false;

  const assunto = `O técnico ${nomeTecnico} precisa abastecer (${s.placa})`;
  const corpo =
    `Prezado Glaydson,\n\n` +
    `O técnico ${nomeTecnico} precisa abastecer o veículo ${s.placa}` +
    `${s.modelo ? ' - ' + s.modelo : ''}${s.cor ? ' (' + s.cor + ')' : ''}.\n\n` +
    `Km atual: ${s.kmAtual}\n` +
    `Km no último abastecimento: ${s.kmUltimoAbastecimento}\n` +
    `Km que couberam no tanque: ${s.kmCoube}\n` +
    `Km rodados desde então: ${s.kmRodados}\n` +
    `Km restantes estimados: ${s.kmRestantes}\n\n` +
    `Atenciosamente,\nControle de Abastecimento GCM Serviços`;
  MailApp.sendEmail(destinos, assunto, corpo);
  ss.getSheetByName(ABA.ALERTAS).appendRow([agora, s.placa, nomeTecnico, s.kmRestantes, destinos]);
  return true;
}

function salvarFoto_(base64, placa, data) {
  const pastas = DriveApp.getFoldersByName(PASTA_FOTOS);
  const pasta = pastas.hasNext() ? pastas.next() : DriveApp.createFolder(PASTA_FOTOS);
  const nome = `${placa}_${Utilities.formatDate(data, 'America/Sao_Paulo', 'yyyyMMdd_HHmmss')}.jpg`;
  const blob = Utilities.newBlob(Utilities.base64Decode(base64), 'image/jpeg', nome);
  return pasta.createFile(blob).getUrl();
}

function linhas_(aba) {
  const n = aba.getLastRow();
  return n < 2 ? [] : aba.getRange(2, 1, n - 1, aba.getLastColumn()).getValues();
}

function chaveValida_(chave) {
  const esperada = PropertiesService.getScriptProperties().getProperty('CHAVE_APP');
  return esperada && chave === esperada;
}

function json_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}
