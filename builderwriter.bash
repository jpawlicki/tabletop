#!bash

read -r classname;
readarray elements;

echo -e "final class $classname {";
for i in ${!elements[@]}; do
	a=(${elements[$i]});
	echo -e "\tpublic final ${a[0]} ${a[1]};";
done;

echo '';
echo -e "\tprivate $classname(";
for i in ${!elements[@]}; do
	a=(${elements[$i]});
	echo -e -n "\t\t\t${a[0]} ${a[1]}";
	if [ $i -lt $(( ${#elements[@]} - 1 )) ]; then
		echo ',';
	else
		echo ') {';
	fi;
done;
for i in ${!elements[@]}; do
	a=(${elements[$i]});
	echo -e "\t\tthis.${a[1]} = ${a[1]};";
done;
echo -e "\t}"

echo '';
echo -e "\tpublic static class Builder {"
for i in ${!elements[@]}; do
	a=(${elements[$i]});
	echo -e "\t\tprivate ${a[0]} ${a[1]};";
done;

echo '';
echo -e "\t\tpublic Builder() {}"
echo -e "\t\tpublic Builder(Builder o) {"
for i in ${!elements[@]}; do
	a=(${elements[$i]});
	echo -e "\t\t\tthis.${a[1]} = o.${a[1]};";
done;
echo -e "\t\t}"
for i in ${!elements[@]}; do
	a=(${elements[$i]});
	echo -e "\t\tpublic Builder set${a[1]^}(${a[0]} ${a[1]}) {";
	echo -e "\t\t\tthis.${a[1]} = ${a[1]};";
	echo -e "\t\t\treturn this;"
	echo -e "\t\t}";
done;
echo -e "\t\tpublic $classname build() {";
echo -e "\t\t\treturn new $classname("
for i in ${!elements[@]}; do
	a=(${elements[$i]});
	echo -e -n "\t\t\t\t\t${a[1]}";
	if [ $i -lt $(( ${#elements[@]} - 1 )) ]; then
		echo ',';
	else
		echo ');';
	fi;
done;
echo -e "\t}";
echo -e "}";
