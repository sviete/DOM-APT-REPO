import hashlib, os
DISTRIBUTION = 'dom'
COMPONENT = 'stable'
arch = 'arm'
output_path = '/home/andrzej/Projects/DOM-APT-REPO'
distribution_path = output_path + '/dists/' + DISTRIBUTION
component_path = distribution_path + '/' + COMPONENT
arch_dir_path = component_path + '/binary-' + arch
packages_file_path = arch_dir_path + '/Packages'
packagesxz_file_path = packages_file_path + '.xz'
binary_path = 'binary-' + arch

print(' ' + hashlib.sha256(open(packages_file_path, 'rb').read()).hexdigest()
            + ' '
            + str(os.stat(packages_file_path).st_size)
            + ' ' + COMPONENT + '/' + binary_path + '/Packages')

print(' ' + hashlib.sha256(open(packagesxz_file_path, 'rb').read()).hexdigest()
            + ' '
            + str(os.stat(packagesxz_file_path).st_size)
            + ' ' + COMPONENT + '/' + binary_path + '/Packages.xz')