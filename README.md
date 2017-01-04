# clj-wiki

A wiki made with Clojure, Yada and Datomic Client.

More info in this blogpost: [http://thegeez.net/2017/01/04/wiki_clojure_yada_datomic_client.html](http://thegeez.net/2017/01/04/wiki_clojure_yada_datomic_client.html)

This is based on the [Edge](https://github.com/juxt/edge) example project. See that projects README for more instructions.

## Libraries
- [Yada](https://github.com/juxt/yada)
- [Datomic Client](http://www.datomic.com/)
- [SimpleMDE](https://simplemde.com/) Markdown editor
- [google-diff-match-patch](https://bitbucket.org/cowwoc/google-diff-match-patch/wiki/Home) for diff and patches


## Running locally

### Datomic Client
Get Datomic Pro from [datomic.com](http://www.datomic.com/).
Run a Datomic peer server:
```
datomic-pro-0.9.5544/bin/run -m datomic.peer-server -p 8998 -a wiki,wiki -d wiki,datomic:mem://wiki
```
Make sure the used settings are the same as in `resources/config.edn`.

### Create a location for the file server
Make sure the directory in `resources/config.edn` under the `[:fs2 :private-path]` path points to a folder that is writeable.

### Run the webserver
```
boot dev
```
See the [Edge README.md](https://github.com/juxt/edge/blob/master/README.md) for helpfull tips for repl and CIDER support.

## Copyright & License

The MIT License (MIT)

Edge: Copyright © 2016 JUXT LTD.
clj-wiki: Copyright © 2017 TheGeez.net.

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
