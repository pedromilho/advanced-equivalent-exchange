# Advanced Equivalent Exchange

Mod de alquimia para **Hytale 0.7.7**, inspirado no [ProjectE](https://github.com/sinkillerj/ProjectE).

- **Mesa de Transmutação** converte itens em EMC e usa o saldo para recriar itens aprendidos.
- **Baú Alquímico** armazena até 104 pilhas.
- **Condensador de Energia** usa o EMC dos itens depositados para copiar um item escolhido. Tem dois tiers.
- **Pedra Filosofal** é usada na receita da Mesa de Transmutação.

Encontre as receitas na aba **Troca Equivalente** da Bancada de Alquimia.

## Compilação e instalação

Com o JDK 25 e o `HytaleServer.jar` da versão 0.7.7, execute no PowerShell.

```powershell
.\build.ps1 -ServerJar 'caminho\para\HytaleServer.jar'
```

Copie o arquivo `dist/AdvancedEquivalentExchange-0.1.0.jar` para `UserData/Mods/` e habilite o mod nas opções do mundo. Em um servidor dedicado, coloque o JAR em `mods/` e reinicie o servidor.
