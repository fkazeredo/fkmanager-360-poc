# O simulador expõe semântica legada sobre transporte simples

O `simulador-core-legado` oferece um contrato host-centric — vocabulário e representação nascidos
no legado — transportado por HTTP/REST. O que é legado é a **semântica do contrato**, não o
transporte.

Isso não afirma que mainframe necessariamente expõe JSON ruim: um mainframe real pode estar atrás
de uma camada moderna de API com OpenAPI impecável. A simulação escolhida é outra, e é
deliberada — o sistema moderno recebeu acesso a uma interface existente cujo vocabulário nasceu no
host. É essa situação, e não o mainframe em si, que torna a Anti-Corruption Layer visivelmente
necessária.

O contrato usa nomes abreviados (`COD-CLI`, `VLR-LIM-CHQ-ESP`, `DAT-ATU-LIM`), códigos numéricos
no lugar de enums descritivos, datas `yyyyMMdd`, dinheiro em centavos sem separador e com
zero-padding, campos opcionais representados por blanks, códigos de retorno próprios, e
identificadores de protocolo do host. O status HTTP não representa necessariamente todos os erros
de negócio: uma resposta `200` pode carregar `COD-RET: "117"` com `MSG-RET: "CONTA NAO ELEGIVEL"`.

A alternativa — um simulador falando REST/JSON limpo em pt-BR como os nossos serviços — foi
rejeitada porque tornaria a ACL uma camada de cópia de campo, e a pergunta legítima "por que essa
camada existe?" ficaria sem resposta demonstrável.

## Consequências

A ACL distingue **falha de transporte** (timeout, conexão) de **resposta técnica válida com
retorno de erro do host**, e traduz códigos em conceitos: `COD-RET` vira resultado de operação,
`VLR-LIM-CHQ-ESP` vira LimiteChequeEspecial, `SIT-CTA` vira situação da conta, `DAT-ATU-LIM` vira
data. Nenhum desses códigos pode vazar para o domínio, para os casos de uso, para o Angular ou
para o banco de Credito. Valores monetários não usam ponto flutuante.

Ficam fora do escopo, por custo operacional desproporcional ao aprendizado: EBCDIC real, COMP-3,
copybook COBOL completo, IBM MQ e CICS. Quando `Movimentacoes` entrar, o mesmo estilo semântico
reaparece como arquivo posicional com header, detalhe e trailer — suficiente para tornar
necessária a etapa explícita de registro cru, parsing, staging, validação e projeção.
