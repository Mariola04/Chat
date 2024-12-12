#/bin/sh 

#USAGE 
#  ./run.sh c  -> clean
# ./run.sh run <n> -> run server and n clients

if [ "$1" = "c" ]; then 
	rm -rf *.class
	exit
fi

rm -rf *.class && javac -d . ChatClient.java  && javac -d . ChatServer.java
 
if [ "$#" -eq 2 ]; then
	if [ "$1" = "run" ]; then 
		i=0
		# launch n clients
		while [ "$i" -lt "$2" ]; do
			java ChatClient localhost 8000 & disown
			i=$((i + 1))
		done

		# launch server 
		java ChatServer 8000

	fi
fi

