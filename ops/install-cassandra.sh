cd charts
sudo helm install --set replicaCount=$k8cassandra_node_count --name cassandra-cluster ./incubator/cassandra --namespace cassandra

cd ..