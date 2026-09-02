# Monorepo, preparado para virar polyrepo

Todos os serviços, o Angular e o simulador vivem em um único repositório, subido localmente por um
comando só. A separação entre módulos é mantida rígida o suficiente para que extrair um serviço
para repositório próprio seja um movimento mecânico, e não uma cirurgia.

O monorepo foi escolhido porque o objeto da POC é demonstrar Spec-Driven Development, e o valor
está em ver spec, issue, implementação e revisão atravessando vários serviços numa mudança só.
Polyrepo espalharia essa narrativa por seis repositórios e mataria a demonstração — além de
fragmentar o backlog, que aqui vive num tracker único.

"Preparado para polyrepo" é uma restrição real, não uma intenção: nada de dependência entre
módulos que não seja explícita, nada de entidade de domínio compartilhada entre bounded contexts
(ADR-0003), nada de banco compartilhado entre serviços.

## Consequências

Conveniências típicas de monorepo que criariam acoplamento indevido ficam proibidas mesmo quando
funcionariam — em particular, importar classes de domínio de outro contexto por estarem no mesmo
repositório. O ganho de ergonomia não paga o custo de perder a fronteira.
