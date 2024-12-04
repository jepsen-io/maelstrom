(defproject maelstrom "0.2.4"
  :description "A test bench for writing toy distributed systems"
  :url "https://github.com/jepsen-io/maelstrom"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :main maelstrom.core
  :jvm-opts ["-Djava.awt.headless=true"
             ; "-agentpath:/home/aphyr/yourkit/bin/linux-x86-64/libyjpagent.so=sampling,exceptions=disable,probe_disable=*"
             ]
  :dependencies [[org.clojure/clojure "1.12.0"]
                 [jepsen "0.3.7"]
                 [amalloy/ring-buffer "1.3.1"]
                 [cheshire "5.13.0"]
                 [byte-streams "0.2.4"]
                 ; Reductions over journals
                 [tesser.core "1.0.6"]
                 [tesser.math "1.0.6"]
                 ; For range sets
                 [com.google.guava/guava "30.1-jre"]
                 ; Input validation
                 [prismatic/schema "1.4.1"]
                 ; Random distributions
                 [incanter/incanter-core "1.9.3"]
                 ]
  :profiles {:uberjar {:aot [maelstrom.core]}})
