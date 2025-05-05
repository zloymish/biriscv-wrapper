# fork

Bumped to chipyard 1.13.0. Here is how to launch:

1) Clone the repo

2) Move the repo to generators/ directory and set the name as "biriscv"

3) Add file BiRISCVConfig.scala to generators/chipyard/src/main/scala/config with the following text:

```scala
package chipyard

import biriscv._
import org.chipsalliance.cde.config.{Config}

class BiRISCVConfig extends Config(
  new WithNBiRISCVCores(1) ++
  new chipyard.config.AbstractConfig)
```

4) Edit build.sbt in chipyard root. Change dependencies of project "chipyard":

```scala
lazy val chipyard = (project in file("generators/chipyard"))
  .dependsOn(..., biriscv)
```

Add lines at the end of file:

```scala
lazy val biriscv = (project in file("generators/biriscv"))
  .dependsOn(
    rocketchip, 
    cde,
    diplomacy
   )
  .settings(libraryDependencies ++= rocketLibDeps.value)
  .settings(
    chiselSettings,
    commonSettings,
  )
```

5) Make simulation

```
cd sims/verilator
make CONFIG=BiRISCVConfig
```

# biRISC-V Wrapper (original readme)

This wraps up the biRISC-V 32-bit dual issue RISC-V CPU
 (https://github.com/ultraembedded/biriscv) into a Rocket Chip based tile to be used in Chipyard.

For more information on how to use this wrapper, refer to (https://github.com/ucb-bar/chipyard).
