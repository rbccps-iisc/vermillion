#!/bin/bash

#TODO: Make sure paths are correct when running this file

docker run --rm -ti --network single-node_vermillion-net -v ${PWD}/data:/tmp elasticdump/elasticsearch-dump \
    --input=http://elasticsearch:9200/archive \
    --fsCompress \
    --overwrite=true \
    --limit=10000 \
    --output=/tmp/archive.json.gz \
    --type=data

